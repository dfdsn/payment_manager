package com.malyah.accountmanager.installments.application;

import java.util.List;

/**
 * H05.3 preview: what the operation would change and what it preserves. The token summarizes this exact impact; the
 * application recalculates it under lock and refuses any difference.
 */
public record InstallmentImpactView(String changeType, String impactToken, List<AffectedInstallmentView> affected,
        List<PreservedInstallmentView> preserved, String affectedAmount, InstallmentPreviewView replacement) {
    public InstallmentImpactView {
        affected = List.copyOf(affected);
        preserved = List.copyOf(preserved);
    }
}
