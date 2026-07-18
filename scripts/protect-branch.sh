#!/usr/bin/env bash
# Protect a branch on GitHub (default: main).
#
# Requires the GitHub CLI (https://cli.github.com) authenticated with admin
# rights on the repo:  gh auth login
#
# Usage:
#   ./scripts/protect-branch.sh                 # protect main
#   ./scripts/protect-branch.sh my-branch       # protect another branch
#   REPO=owner/name ./scripts/protect-branch.sh # override repo detection
#
# Applied rules:
#   - CI ("Build, lint, test") must pass before merging
#   - branch must be up to date with base before merging (strict checks)
#   - force pushes and deletions blocked
#   - PR review requirement OFF (solo-friendly; flip REQUIRE_REVIEWS=1 to enable)
set -euo pipefail

BRANCH="${1:-main}"
REPO="${REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
REQUIRE_REVIEWS="${REQUIRE_REVIEWS:-0}"

if [[ "$REQUIRE_REVIEWS" == "1" ]]; then
  REVIEWS='{"required_approving_review_count":1}'
else
  REVIEWS='null'
fi

echo "Protecting $REPO@$BRANCH ..."
gh api --method PUT "repos/$REPO/branches/$BRANCH/protection" \
  --input - <<EOF
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["Build, lint, test"]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": $REVIEWS,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
EOF

echo "Done. Current protection:"
gh api "repos/$REPO/branches/$BRANCH/protection" -q '{
  checks: .required_status_checks.contexts,
  strict: .required_status_checks.strict,
  force_pushes: .allow_force_pushes.enabled,
  deletions: .allow_deletions.enabled
}'
