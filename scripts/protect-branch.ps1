# Protect a branch on GitHub (default: main). PowerShell twin of protect-branch.sh.
#
# Requires the GitHub CLI (https://cli.github.com) authenticated with admin
# rights on the repo:  gh auth login
#
# This PUT asserts a COMPLETE desired state, not a patch. The branch
# protection API replaces the whole object on every call, so any field
# this script omits silently reverts to the API default (false) the next
# time it runs -- even if someone turned that field on by hand in the
# GitHub UI in between. Every field this endpoint exposes is therefore
# sent explicitly below, not just the ones this script cares about
# changing. The one field left out on purpose is required_signatures:
# GitHub manages it through its own endpoint
# (.../protection/required_signatures, PUT to enable / DELETE to
# disable), it is not part of this object at all, and this script does
# not assert it -- check it separately if it ever matters.
#
# Usage:
#   .\scripts\protect-branch.ps1                          # protect main
#   .\scripts\protect-branch.ps1 -Branch my-branch        # protect another branch
#   .\scripts\protect-branch.ps1 -RequireReviews          # also require 1 PR approval
#   .\scripts\protect-branch.ps1 -NoEnforceAdmins         # exempt admins (weakens live protection)
#   .\scripts\protect-branch.ps1 -NoRequireLinearHistory  # allow merge commits (weakens live protection)
#
# Branch creation, conversation resolution, branch locking and fork
# syncing are hardcoded to the GitHub default (off), matching live main
# as read on 2026-08-16 -- there is no flag for these; edit the JSON
# below directly if that default ever needs to change.
param(
    [string]$Branch = "main",
    [string]$Repo = "",
    [switch]$RequireReviews,
    [switch]$NoEnforceAdmins,
    [switch]$NoRequireLinearHistory
)
$ErrorActionPreference = "Stop"

if (-not $Repo) {
    $Repo = gh repo view --json nameWithOwner -q .nameWithOwner
}
$reviews = if ($RequireReviews) { '{"required_approving_review_count":1}' } else { 'null' }
$enforceAdmins = if ($NoEnforceAdmins) { 'false' } else { 'true' }
$linearHistory = if ($NoRequireLinearHistory) { 'false' } else { 'true' }

$body = @"
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["Build, lint, test"]
  },
  "enforce_admins": $enforceAdmins,
  "required_pull_request_reviews": $reviews,
  "required_linear_history": $linearHistory,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "block_creations": false,
  "required_conversation_resolution": false,
  "lock_branch": false,
  "allow_fork_syncing": false
}
"@

Write-Host "Protecting $Repo@$Branch ..."
$body | gh api --method PUT "repos/$Repo/branches/$Branch/protection" --input -

Write-Host "Done. Current protection (full object, every field this endpoint returns):"
gh api "repos/$Repo/branches/$Branch/protection"
