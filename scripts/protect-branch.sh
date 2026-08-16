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
# Applied rules:
#   - CI ("Build, lint, test") must pass before merging
#   - branch must be up to date with base before merging (strict checks)
#   - force pushes and deletions blocked
#   - admins are bound by the same rules (flip ENFORCE_ADMINS=0 to exempt them --
#     this WEAKENS live protection, so it is opt-out, not the default)
#   - linear history required, no merge commits (flip REQUIRE_LINEAR_HISTORY=0
#     to allow them -- also opt-out, also a weakening)
#   - PR review requirement OFF (solo-friendly; flip REQUIRE_REVIEWS=1 to enable)
#   - branch creation, conversation resolution, branch locking and fork
#     syncing are hardcoded to the GitHub default (off), matching live
#     main as read on 2026-08-16 -- there is no flag for these; edit the
#     JSON below directly if that default ever needs to change
set -euo pipefail

BRANCH="${1:-main}"
REPO="${REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
REQUIRE_REVIEWS="${REQUIRE_REVIEWS:-0}"
ENFORCE_ADMINS="${ENFORCE_ADMINS:-1}"
REQUIRE_LINEAR_HISTORY="${REQUIRE_LINEAR_HISTORY:-1}"

if [[ "$REQUIRE_REVIEWS" == "1" ]]; then
  REVIEWS='{"required_approving_review_count":1}'
else
  REVIEWS='null'
fi

# Branch on the OPT-OUT value ("0"), never the opt-in one. Branching on
# =="1" fails OPEN for every other spelling an operator might type --
# ENFORCE_ADMINS=true, =yes, =TRUE would all silently turn enforcement
# OFF instead of on, which is the opposite of what someone typing
# "true" while trying to strengthen protection would expect.
if [[ "$ENFORCE_ADMINS" == "0" ]]; then
  ENFORCE_ADMINS_JSON='false'
else
  ENFORCE_ADMINS_JSON='true'
fi

if [[ "$REQUIRE_LINEAR_HISTORY" == "0" ]]; then
  LINEAR_HISTORY_JSON='false'
else
  LINEAR_HISTORY_JSON='true'
fi

echo "Protecting $REPO@$BRANCH ..."
gh api --method PUT "repos/$REPO/branches/$BRANCH/protection" \
  --input - <<EOF
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["Build, lint, test"]
  },
  "enforce_admins": $ENFORCE_ADMINS_JSON,
  "required_pull_request_reviews": $REVIEWS,
  "required_linear_history": $LINEAR_HISTORY_JSON,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "block_creations": false,
  "required_conversation_resolution": false,
  "lock_branch": false,
  "allow_fork_syncing": false
}
EOF

echo "Done. Current protection (full object, every field this endpoint returns):"
gh api "repos/$REPO/branches/$BRANCH/protection"
