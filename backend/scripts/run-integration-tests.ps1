param(
    [Parameter()]
    [ValidatePattern('^[A-Za-z_$][A-Za-z0-9_.$]*$')]
    [string[]]$Tests = @()
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

$backendRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$wrapper = Join-Path $backendRoot 'mvnw.cmd'
$reportDirectory = [System.IO.Path]::GetFullPath((Join-Path $backendRoot 'target\failsafe-reports'))
$expectedReportDirectory = [System.IO.Path]::GetFullPath(
    (Join-Path (Join-Path $backendRoot 'target') 'failsafe-reports'))

if ($reportDirectory -ne $expectedReportDirectory -or
    -not $reportDirectory.StartsWith($backendRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    [Console]::Error.WriteLine("Diretório de relatórios inesperado: $reportDirectory")
    exit 11
}

if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    [Console]::Error.WriteLine("Maven Wrapper não encontrado: $wrapper")
    exit 12
}

$requiredCommands = @{}
foreach ($commandName in @('java.exe', 'docker.exe', 'powershell.exe')) {
    $command = Get-Command $commandName -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        [Console]::Error.WriteLine("Comando obrigatório não encontrado no PATH: $commandName")
        exit 13
    }
    $requiredCommands[$commandName] = $command.Source
}

# Algumas instalações Windows acumulam entradas PATH concatenadas e inválidas.
# A limpeza é restrita a este processo e preserva todos os caminhos válidos,
# além dos diretórios de Java, Docker, PowerShell e ferramentas do Windows.
$pathEntries = [System.Collections.Generic.List[string]]::new()
$seenEntries = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase)

function Add-ProcessPathEntry([string]$entry) {
    if ([string]::IsNullOrWhiteSpace($entry)) { return }
    try {
        $fullEntry = [System.IO.Path]::GetFullPath($entry.Trim())
    } catch {
        Write-Warning "Entrada PATH inválida ignorada apenas neste processo: $entry"
        return
    }
    if ($seenEntries.Add($fullEntry)) {
        $pathEntries.Add($fullEntry)
    }
}

foreach ($commandPath in $requiredCommands.Values) {
    Add-ProcessPathEntry (Split-Path -Parent $commandPath)
}
Add-ProcessPathEntry ([Environment]::GetFolderPath('System'))
Add-ProcessPathEntry $env:SystemRoot
foreach ($entry in ($env:PATH -split [System.IO.Path]::PathSeparator)) {
    Add-ProcessPathEntry $entry
}
$env:PATH = $pathEntries -join [System.IO.Path]::PathSeparator

Write-Output 'Verificando acesso ao servidor Docker...'
& $requiredCommands['docker.exe'] version
$dockerExitCode = $LASTEXITCODE
if ($dockerExitCode -ne 0) {
    [Console]::Error.WriteLine(
        "Servidor Docker inacessível (código $dockerExitCode). " +
        'O erro original está acima. O script não eleva privilégios nem altera o Docker.')
    exit $dockerExitCode
}

if (Test-Path -LiteralPath $reportDirectory) {
    Remove-Item -LiteralPath $reportDirectory -Recurse -Force
}

$selectedTests = @($Tests | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
$mavenArguments = @('verify')
if ($selectedTests.Count -gt 0) {
    $selector = $selectedTests -join ','
    $mavenArguments += "-Dit.test=$selector"
    Write-Output "Executando testes de integração selecionados: $selector"
} else {
    Write-Output 'Executando toda a suíte de integração configurada no Maven Failsafe.'
}

$executionStartedUtc = [DateTime]::UtcNow
Push-Location $backendRoot
try {
    & $wrapper @mavenArguments
    $mavenExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

$reportFiles = @()
if (Test-Path -LiteralPath $reportDirectory) {
    $reportFiles = @(Get-ChildItem -LiteralPath $reportDirectory -Filter 'TEST-*.xml' -File |
        Where-Object { $_.LastWriteTimeUtc -ge $executionStartedUtc })
}

$reportRows = @()
foreach ($reportFile in $reportFiles) {
    [xml]$report = Get-Content -LiteralPath $reportFile.FullName
    $suite = $report.testsuite
    $reportRows += [pscustomobject]@{
        TestSuite = [string]$suite.name
        Tests = [int]$suite.tests
        Skipped = [int]$suite.skipped
        Failures = [int]$suite.failures
        Errors = [int]$suite.errors
        Seconds = [decimal]$suite.time
        Report = $reportFile.FullName
    }
}

Write-Output ''
Write-Output 'Resumo dos relatórios Failsafe gerados nesta execução:'
if ($reportRows.Count -eq 0) {
    Write-Output 'Nenhum relatório TEST-*.xml novo foi gerado.'
} else {
    $reportRows | Format-Table TestSuite, Tests, Skipped, Failures, Errors, Seconds -AutoSize
}

$totalTests = ($reportRows | Measure-Object -Property Tests -Sum).Sum
$totalSkipped = ($reportRows | Measure-Object -Property Skipped -Sum).Sum
$totalFailures = ($reportRows | Measure-Object -Property Failures -Sum).Sum
$totalErrors = ($reportRows | Measure-Object -Property Errors -Sum).Sum
if ($null -eq $totalTests) { $totalTests = 0 }
if ($null -eq $totalSkipped) { $totalSkipped = 0 }
if ($null -eq $totalFailures) { $totalFailures = 0 }
if ($null -eq $totalErrors) { $totalErrors = 0 }

Write-Output (
    "TOTAL: executados=$totalTests; ignorados=$totalSkipped; " +
    "falhas=$totalFailures; erros=$totalErrors")

$validationIncomplete = $reportRows.Count -eq 0 -or $totalTests -le 0 -or $totalSkipped -gt 0
foreach ($selectedTest in $selectedTests) {
    $matchingReports = @($reportRows | Where-Object {
        $_.TestSuite -eq $selectedTest -or $_.TestSuite.EndsWith(".$selectedTest")
    })
    $selectedExecuted = ($matchingReports | Measure-Object -Property Tests -Sum).Sum
    $selectedSkipped = ($matchingReports | Measure-Object -Property Skipped -Sum).Sum
    if ($null -eq $selectedExecuted) { $selectedExecuted = 0 }
    if ($null -eq $selectedSkipped) { $selectedSkipped = 0 }
    if ($matchingReports.Count -eq 0 -or $selectedExecuted -le 0 -or $selectedSkipped -gt 0) {
        [Console]::Error.WriteLine(
            "Validação incompleta para '$selectedTest': " +
            "relatórios=$($matchingReports.Count), executados=$selectedExecuted, ignorados=$selectedSkipped.")
        $validationIncomplete = $true
    }
}

if ($mavenExitCode -ne 0) {
    [Console]::Error.WriteLine("Maven terminou com código $mavenExitCode.")
    exit $mavenExitCode
}
if ($validationIncomplete) {
    [Console]::Error.WriteLine(
        'Maven terminou com sucesso, mas a validação de integração está incompleta. Consulte o resumo acima.')
    exit 20
}

Write-Output 'Validação de integração concluída com relatórios novos e sem testes ignorados.'
exit 0
