#!/usr/bin/env bash
# Create an Android release signing key and store it ONLY as GitHub Actions secrets,
# then wipe the local copy. Needs: gh (logged in), openssl, base64, mktemp. No Java.
#
# The same key can sign several apps. GitHub has no account-level Actions secrets for
# personal accounts, so sharing works like this:
#   - organization: the four secrets are created once at organization level and
#     granted to the repositories you list; later repositories are granted with --grant
#     (no key needed, the secrets are never read back);
#   - personal account: the same values are written to every repository you list in
#     this one run. Adding a repository later means a new key (and reinstalls), so list
#     all of them now, or move the apps into an organization.
#
# Usage (run from a clone of the app repository, or pass the target explicitly):
#   scripts/setup-signing.sh                         # infer org/repo from the current clone
#   scripts/setup-signing.sh --org ORG --repos grape,otherapp
#   scripts/setup-signing.sh --org ORG --all-repos   # every repo in the org may use it
#   scripts/setup-signing.sh --repo OWNER/REPO --repo OWNER/REPO2
#   scripts/setup-signing.sh --org ORG --grant ORG/newapp     # share the existing org key
#   scripts/setup-signing.sh --dry-run ...           # show what would be done, set nothing
#
# Secrets written (the names the Release workflow expects):
#   KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
set -euo pipefail

SECRET_NAMES=(KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD)
ALIAS="release"
ORG=""
REPOS=()
GRANTS=()
VISIBILITY=""
DRY_RUN=0
LABEL=""

usage() { sed -n '2,25p' "$0"; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --org) ORG="${2:?}"; shift 2 ;;
    --repo) REPOS+=("${2:?}"); shift 2 ;;
    --repos) IFS=, read -r -a extra <<< "${2:?}"; REPOS+=("${extra[@]}"); shift 2 ;;
    --all-repos) VISIBILITY="all"; shift ;;
    --grant) GRANTS+=("${2:?}"); shift 2 ;;
    --label) LABEL="${2:?}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "unknown option: $1" >&2; usage 1 ;;
  esac
done

die() { echo "error: $*" >&2; exit 1; }
say() { printf '%s\n' "$*"; }
run() { if [ "$DRY_RUN" = 1 ]; then say "  [dry-run] $*"; else "$@"; fi; }

# ---- prerequisites ------------------------------------------------------------
for tool in gh openssl base64 mktemp; do
  command -v "$tool" > /dev/null 2>&1 || die "$tool is required"
done
if [ "$DRY_RUN" = 0 ]; then
  gh auth status > /dev/null 2>&1 || die "gh is not logged in; run: gh auth login"
fi

# ---- work out the target ------------------------------------------------------
if [ -z "$ORG" ] && [ ${#REPOS[@]} -eq 0 ] && [ ${#GRANTS[@]} -eq 0 ]; then
  nwo="$(gh repo view --json nameWithOwner --jq .nameWithOwner 2> /dev/null || true)"
  if [ -z "$nwo" ]; then
    url="$(git remote get-url origin 2> /dev/null || true)"
    nwo="$(printf '%s' "$url" | sed -E 's#^.*github\.com[:/]##; s#\.git$##; s#/$##')"
  fi
  [[ "$nwo" == */* ]] || die "not inside a GitHub repository clone; pass --org or --repo"
  owner_type="$(gh api "repos/$nwo" --jq .owner.type 2> /dev/null || echo User)"
  if [ "$owner_type" = "Organization" ]; then
    ORG="${nwo%%/*}"
    REPOS=("${nwo##*/}")
  else
    REPOS=("$nwo")
  fi
  say "Target: $nwo ($owner_type account)"
fi
if [ -n "$ORG" ]; then
  # organization secrets take bare repository names
  for i in "${!REPOS[@]}"; do REPOS[$i]="${REPOS[$i]##*/}"; done
  [ -n "$VISIBILITY" ] || VISIBILITY="selected"
  [ "$VISIBILITY" = "all" ] || [ ${#REPOS[@]} -gt 0 ] || [ ${#GRANTS[@]} -gt 0 ] \
    || die "with --org, pass --repos, --all-repos or --grant"
else
  [ ${#REPOS[@]} -gt 0 ] || die "pass --repo OWNER/REPO (or --org ORG)"
  [ ${#GRANTS[@]} -eq 0 ] || die "--grant needs --org: personal accounts have no shared Actions secrets"
  for r in "${REPOS[@]}"; do [[ "$r" == */* ]] || die "--repo needs OWNER/REPO, got '$r'"; done
fi

# ---- --grant: let more repositories use the existing organization secrets -----
if [ ${#GRANTS[@]} -gt 0 ]; then
  existing="$(gh secret list --org "$ORG" --json name --jq '.[].name' 2> /dev/null || true)"
  for n in "${SECRET_NAMES[@]}"; do
    grep -qx "$n" <<< "$existing" || die "organization secret $n does not exist yet; run without --grant first"
  done
  for g in "${GRANTS[@]}"; do
    [[ "$g" == */* ]] || g="$ORG/$g"
    id="$(gh api "repos/$g" --jq .id)"
    for n in "${SECRET_NAMES[@]}"; do
      run gh api --method PUT "orgs/$ORG/actions/secrets/$n/repositories/$id" > /dev/null
    done
    say "granted $g access to the organization signing secrets"
  done
  exit 0
fi

# ---- generate the key ---------------------------------------------------------
WORK="$(mktemp -d)"
chmod 700 "$WORK"
secure_delete() {
  local f
  for f in "$WORK"/*; do
    [ -f "$f" ] || continue
    if command -v shred > /dev/null 2>&1; then shred -u "$f"
    elif rm -P "$f" 2> /dev/null; then :
    else dd if=/dev/urandom of="$f" bs=4096 count=8 conv=notrunc > /dev/null 2>&1 || true; rm -f "$f"; fi
  done
  rmdir "$WORK" 2> /dev/null || true
  unset PASS
}
trap secure_delete EXIT

[ -n "$LABEL" ] || LABEL="${ORG:-${REPOS[0]%%/*}}"
PASS="$(openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-40)"
export PASS
[ "${#PASS}" -ge 32 ] || die "could not generate a password"

say "Generating a 4096-bit RSA key and a 100-year self-signed certificate..."
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out "$WORK/key.pem" 2> /dev/null
openssl req -x509 -new -key "$WORK/key.pem" -sha256 -days 36500 \
  -subj "/CN=$LABEL Android release/O=$LABEL" -out "$WORK/cert.pem" 2> /dev/null
# 3DES PBE is understood by every JDK that Android Gradle supports.
openssl pkcs12 -export -inkey "$WORK/key.pem" -in "$WORK/cert.pem" -name "$ALIAS" \
  -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES \
  -passout env:PASS -out "$WORK/release.p12"
# round-trip check: the keystore opens with the password and holds the certificate
FINGERPRINT="$(openssl pkcs12 -in "$WORK/release.p12" -passin env:PASS -nokeys -passout pass: 2> /dev/null \
  | openssl x509 -noout -fingerprint -sha256 | sed 's/.*=//')"
[ -n "$FINGERPRINT" ] || die "keystore self-check failed"
base64 < "$WORK/release.p12" | tr -d '\n' > "$WORK/release.b64"
say "Certificate SHA-256: $FINGERPRINT"

# ---- store as secrets ---------------------------------------------------------
set_secret() { # name file-with-value [gh args...]
  local name="$1" file="$2"; shift 2
  if [ "$DRY_RUN" = 1 ]; then
    say "  [dry-run] gh secret set $name --app actions $* < <value>"
  else
    gh secret set "$name" --app actions "$@" < "$file"
  fi
}
printf '%s' "$PASS" > "$WORK/pass"
printf '%s' "$ALIAS" > "$WORK/alias"

if [ -n "$ORG" ]; then
  args=(--org "$ORG" --visibility "$VISIBILITY")
  [ "$VISIBILITY" = "selected" ] && args+=(--repos "$(IFS=,; echo "${REPOS[*]}")")
  say "Storing organization secrets in $ORG (visibility: $VISIBILITY${REPOS[*]:+, repos: ${REPOS[*]}})..."
  if ! set_secret KEYSTORE_BASE64 "$WORK/release.b64" "${args[@]}"; then
    die "could not write organization secrets; the token may lack the admin:org scope (gh auth refresh -s admin:org)"
  fi
  set_secret KEYSTORE_PASSWORD "$WORK/pass" "${args[@]}"
  set_secret KEY_ALIAS "$WORK/alias" "${args[@]}"
  set_secret KEY_PASSWORD "$WORK/pass" "${args[@]}"
  targets=("org:$ORG")
else
  for r in "${REPOS[@]}"; do
    say "Storing repository secrets in $r..."
    set_secret KEYSTORE_BASE64 "$WORK/release.b64" --repo "$r"
    set_secret KEYSTORE_PASSWORD "$WORK/pass" --repo "$r"
    set_secret KEY_ALIAS "$WORK/alias" --repo "$r"
    set_secret KEY_PASSWORD "$WORK/pass" --repo "$r"
  done
  targets=("${REPOS[@]}")
fi

# ---- verify before the local copy disappears ----------------------------------
if [ "$DRY_RUN" = 0 ]; then
  for t in "${targets[@]}"; do
    if [[ "$t" == org:* ]]; then names="$(gh secret list --org "${t#org:}" --json name --jq '.[].name')"
    else names="$(gh secret list --repo "$t" --json name --jq '.[].name')"; fi
    for n in "${SECRET_NAMES[@]}"; do
      grep -qx "$n" <<< "$names" || die "secret $n is missing in $t after upload; the local key will still be wiped, run again"
    done
  done
fi

say
say "Done. The signing key now exists only as GitHub Actions secrets; the local copy is being wiped."
say "Certificate SHA-256 (public, for the record): $FINGERPRINT"
say
say "Next: push a tag to publish a signed release, e.g.  git tag v0.1.0 && git push origin v0.1.0"
if [ -n "$ORG" ] && [ "$VISIBILITY" = "selected" ]; then
  say "To let another repository in $ORG use the same key:  $0 --org $ORG --grant $ORG/<repo>"
fi
