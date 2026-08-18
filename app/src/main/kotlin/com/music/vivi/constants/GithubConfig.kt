package com.music.vivi.constants

/**
 * Centralized GitHub repository configuration for ViVi Fork.
 * All in-app updater, changelog, commits, and web links reference these constants.
 */
object GithubConfig {
    const val REPO_OWNER = "daveans1"
    const val REPO_NAME = "vivi-music"
    const val DEFAULT_BRANCH = "main"

    val REPO_URL = "https://github.com/$REPO_OWNER/$REPO_NAME"
    val RELEASES_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases"
    val COMMITS_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/commits?branch=$DEFAULT_BRANCH&per_page=50"
    val NIGHTLY_ACTIONS_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/actions/workflows/build.yml/runs?status=success&per_page=100"
    val NIGHTLY_DOWNLOAD_URL = "https://nightly.link/$REPO_OWNER/$REPO_NAME/workflows/build.yml/$DEFAULT_BRANCH/app-universal-gms-release.zip"
    val ISSUES_URL = "$REPO_URL/issues"
    val DISCUSSIONS_URL = "$REPO_URL/discussions"
    val LATEST_RELEASE_URL = "$REPO_URL/releases/latest"
    val RELEASES_PAGE_URL = "$REPO_URL/releases"
}
