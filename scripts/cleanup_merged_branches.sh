#!/usr/bin/env bash
set -euo pipefail

REMOTE="${GIT_REMOTE:-origin}"
PRIMARY="${PRIMARY_BRANCH:-main}"
MODE="${CLEANUP_MODE:-dry-run}"
PR_HEADS="${OPEN_PR_HEAD_FILE:-}"

if [[ "$MODE" != "apply" && "$MODE" != "dry-run" ]]; then
    echo "Invalid cleanup mode: $MODE" >&2
    exit 2
fi
if [[ "$MODE" == "apply" && -z "$PR_HEADS" ]]; then
    echo "Apply mode requires a complete open PR head snapshot" >&2
    exit 2
fi
if [[ -n "$PR_HEADS" && ! -f "$PR_HEADS" ]]; then
    echo "Missing open PR head snapshot" >&2
    exit 2
fi

git fetch "$REMOTE" --prune "+refs/heads/*:refs/remotes/${REMOTE}/*"
main_ref="refs/remotes/${REMOTE}/${PRIMARY}"
main_sha="$(git rev-parse --verify "${main_ref}^{commit}")"

candidates=0
deleted=0
failures=0
skipped=0

while IFS= read -r ref; do
    name="${ref#refs/remotes/${REMOTE}/}"
    case "$name" in
        HEAD|main|master|develop|development|staging|production|gh-pages|stable/*|release/*|support/*|maintenance/*|archive/*|dependabot/*|renovate/*)
            skipped=$((skipped + 1))
            continue
            ;;
    esac
    if [[ "$name" == "$PRIMARY" ]]; then
        skipped=$((skipped + 1))
        continue
    fi
    if [[ -n "$PR_HEADS" ]] && grep -Fxq -- "$name" "$PR_HEADS"; then
        skipped=$((skipped + 1))
        continue
    fi
    sha="$(git rev-parse --verify "${ref}^{commit}")"
    if ! git merge-base --is-ancestor "$sha" "$main_sha"; then
        skipped=$((skipped + 1))
        continue
    fi

    candidates=$((candidates + 1))
    if [[ "$MODE" == "dry-run" ]]; then
        printf 'DRY-RUN %-64s %s\n' "$name" "$sha"
        continue
    fi
    # Optimistic concurrency: if the remote SHA changes, refuse deletion.
    if git push --porcelain \
            --force-with-lease="refs/heads/${name}:${sha}" \
            "$REMOTE" ":refs/heads/${name}"; then
        printf 'DELETED %-64s %s\n' "$name" "$sha"
        deleted=$((deleted + 1))
    else
        printf 'FAILED %-64s %s\n' "$name" "$sha" >&2
        failures=$((failures + 1))
    fi
done < <(git for-each-ref --format='%(refname)' "refs/remotes/${REMOTE}/")

printf 'SUMMARY mode=%s main=%s candidates=%s deleted=%s skipped=%s failures=%s\n' \
    "$MODE" "$main_sha" "$candidates" "$deleted" "$skipped" "$failures"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf 'Branch cleanup: mode=%s; main=%s; candidates=%s; deleted=%s; preserved=%s; failures=%s\n' \
        "$MODE" "$main_sha" "$candidates" "$deleted" "$skipped" "$failures" \
        >> "$GITHUB_STEP_SUMMARY"
fi
if (( failures > 0 )); then
    exit 1
fi
