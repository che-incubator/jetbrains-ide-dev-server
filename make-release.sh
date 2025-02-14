#!/bin/bash
#
# Copyright (c) 2024-2025 Red Hat, Inc.
# This program and the accompanying materials are made
# available under the terms of the Eclipse Public License 2.0
# which is available at https://www.eclipse.org/legal/epl-2.0/
#
# SPDX-License-Identifier: EPL-2.0
#
# Contributors:
#   Red Hat, Inc. - initial API and implementation
#

set -e

usage ()
{   echo "Usage: ./make-release.sh -v <version>"
    exit
}

init() {
  unset VERSION

  while [[ "$#" -gt 0 ]]; do
    case $1 in
      '-v'|'--version') VERSION="$2"; shift 1;;
      '--help'|'-h') usage;;
    esac
    shift 1
  done

  [[ -z ${VERSION} ]] && { echo "[ERROR] Release version is not defined"; usage; }

  X_BRANCH=$(echo "${VERSION}" | sed 's/.$/x/')
  NEXT_BRANCH="pr-main-to-${VERSION}-next"
  NEXT_VERSION="${VERSION}-next"
}

resetChanges() {
  local branch="$1"

  echo "[INFO] Reset changes in ${branch} branch"

  git reset --hard
  git checkout "${branch}"
  git fetch origin --prune
  git pull origin "${branch}"
}

checkoutToXBranch() {
  echo "[INFO] Check out to ${X_BRANCH} branch."

  if [[ $(git ls-remote -q --heads | grep -c "${X_BRANCH}") == 1 ]]; then
    echo "[INFO] ${X_BRANCH} exists."
    resetChanges "${X_BRANCH}"
  else
    echo "[INFO] ${X_BRANCH} does not exist. Will be created a new one from main."
    resetChanges "main"
    git push origin main:"${X_BRANCH}"
    git checkout "${X_BRANCH}"
  fi
}

checkoutToNextBranch() {
  echo "[INFO] Will be created a new ${NEXT_BRANCH} branch from main."

  resetChanges main
  git push origin main:"${NEXT_BRANCH}"
  git checkout "${NEXT_BRANCH}"
}

tagRelease() {
  git tag "${VERSION}"
  git push origin "${VERSION}"
}

createPR() {
  local base=$1
  local branch=$2
  local message=$3

  echo "[INFO] Create PR with base = ${base} and head = ${branch}"
  hub pull-request --base "${base}" --head "${branch}" -m "${message}"
}

updatePackageVersionAndCommitChanges() {
  local version=$1
  local branch=$2
  local message=$3

  echo "[INFO] Set ${version} in package.json"

  npm --no-git-tag-version version --allow-same-version --prefix status-app ${NEXT_VERSION}

  # jq '.version |= "'${version}'"' status-app/package.json > status-app/package.json.update
  # mv -f status-app/package.json.update status-app/package.json

  echo "[INFO] Push changes to ${branch} branch"

  git add status-app/package.json status-app/package-lock.json
  git commit -s -m "${message}"
  git push origin "${branch}"
}

updateXBranch() {
  checkoutToXBranch

  COMMIT_MSG="ci: bump ${VERSION} in ${X_BRANCH}"

  updatePackageVersionAndCommitChanges \
    "${VERSION}" \
    "${X_BRANCH}" \
    "${COMMIT_MSG}"

  tagRelease
}

updateMainBrain() {
  checkoutToNextBranch

  COMMIT_MSG="ci: bump ${NEXT_VERSION} in main"

  updatePackageVersionAndCommitChanges \
    "${NEXT_VERSION}" \
    "${NEXT_BRANCH}" \
    "${COMMIT_MSG}"

  createPR \
    "main" \
    "${NEXT_BRANCH}" \
    "${COMMIT_MSG}"
}

run() {
  updateXBranch
  updateMainBrain
}

init "$@"
run
