# Protect a branch on GitHub (default: main). PowerShell twin of protect-branch.sh.
#
# Requires the GitHub CLI (https://cli.github.com) authenticated with admin
# rights on the repo:  gh auth login
#
# Usage:
#   .\scripts\protect-branch.ps1                     # protect main
#   .\scripts\protect-branch.ps1 -Branch my-branch   # protect another branch
#   .\scripts\protect-branch.ps1 -RequireReviews     # also require 1 PR approval
param(
    [string]$Branch = "main",
    [string]$Repo = "",
    [switch]$RequireReviews
)
$ErrorActionPreference = "Stop"

if (-not $Repo) {
    $Repo = gh repo view --json nameWithOwner -q .nameWithOwner
}
$reviews = if ($RequireReviews) { '{"required_approving_review_count":1}' } else { 'null' }

$body = @"
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["Build, lint, test"]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": $reviews,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
"@

Write-Host "Protecting $Repo@$Branch ..."
$body | gh api --method PUT "repos/$Repo/branches/$Branch/protection" --input -

Write-Host "Done. Current protection:"
gh api "repos/$Repo/branches/$Branch/protection" -q '{checks: .required_status_checks.contexts, strict: .required_status_checks.strict, force_pushes: .allow_force_pushes.enabled, deletions: .allow_deletions.enabled}'
