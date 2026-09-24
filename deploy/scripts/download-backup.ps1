param(
    [Parameter(Mandatory = $true)][string]$RemoteHost,
    [Parameter(Mandatory = $true)][string]$RemoteUser,
    [Parameter(Mandatory = $true)][string]$IdentityFile,
    [Parameter(Mandatory = $true)][string]$RemoteFile,
    [Parameter(Mandatory = $true)][string]$DestinationDirectory
)

$ErrorActionPreference = 'Stop'
$destination = [System.IO.Path]::GetFullPath($DestinationDirectory)
New-Item -ItemType Directory -Path $destination -Force | Out-Null

$name = [System.IO.Path]::GetFileName($RemoteFile)
$temporary = Join-Path $destination ($name + '.partial')
$final = Join-Path $destination $name
$remoteHash = $RemoteFile + '.sha256'
$hashTemporary = $temporary + '.sha256'

scp -i $IdentityFile -- "$RemoteUser@$RemoteHost`:$RemoteFile" $temporary
if ($LASTEXITCODE -ne 0) { throw 'Falha ao baixar backup.' }
scp -i $IdentityFile -- "$RemoteUser@$RemoteHost`:$remoteHash" $hashTemporary
if ($LASTEXITCODE -ne 0) { throw 'Falha ao baixar hash do backup.' }

$expected = ((Get-Content -LiteralPath $hashTemporary -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
$actual = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant()
if ($expected -ne $actual) { throw 'Hash SHA-256 do backup não confere.' }

Move-Item -LiteralPath $temporary -Destination $final -Force
Remove-Item -LiteralPath $hashTemporary
Write-Output "Backup externo confirmado: $final"

