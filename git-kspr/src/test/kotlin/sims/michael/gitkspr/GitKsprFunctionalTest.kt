package sims.michael.gitkspr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.RemoteRefEncoding.buildRemoteRef
import sims.michael.gitkspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitkspr.githubtests.generatedtestdsl.testCase
import sims.michael.gitkspr.testing.FunctionalTest
import kotlin.test.assertEquals

/**
 * Functional test which reads the actual git configuration and interacts with GitHub.
 */
@FunctionalTest
class GitKsprFunctionalTest {

    private val logger = LoggerFactory.getLogger(GitKsprTest::class.java)

    @Test
    fun `push new commits`(testInfo: TestInfo) {
        withTestSetup(useFakeRemote = false) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "feature/1"
                        }
                    }
                },
            )

            System.setProperty(WORKING_DIR_PROPERTY_NAME, localRepo.absolutePath) // TODO why?
            gitKspr.push()

            val testCommits = localGit.log(JGitClient.HEAD, 3)
            val testCommitIds = testCommits.mapNotNull(Commit::id).toSet()
            val remotePrIds = gitHub.getPullRequests(testCommits).mapNotNull(PullRequest::commitId).toSet()
            assertEquals(testCommitIds, remotePrIds)
        }
    }

    @Test
    fun `amend HEAD commit and re-push`(testInfo: TestInfo) {
        withTestSetup(useFakeRemote = false) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                },
            )

            System.setProperty(WORKING_DIR_PROPERTY_NAME, localRepo.absolutePath) // TODO why?
            gitKspr.push()

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit {
                            title = "four"
                            localRefs += "development"
                        }
                    }
                },
            )

            gitKspr.push()

            val testCommits = localGit.log(JGitClient.HEAD, 3)
            val testCommitIds = testCommits.mapNotNull(Commit::id).toSet()
            val remotePrs = gitHub.getPullRequests(testCommits)
            val remotePrIds = remotePrs.mapNotNull(PullRequest::commitId).toSet()
            assertEquals(testCommitIds, remotePrIds)

            val headCommit = localGit.log(JGitClient.HEAD, 1).single()
            val headCommitId = checkNotNull(headCommit.id)
            assertEquals("four", remotePrs.single { it.commitId == headCommitId }.title)
        }
    }

    @Test
    fun `reorder, drop, add, and re-push`(testInfo: TestInfo) {
        withTestSetup(useFakeRemote = false) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "C" }
                        commit { title = "D" }
                        commit {
                            title = "E"
                            localRefs += "main"
                        }
                    }
                },
            )

            System.setProperty(WORKING_DIR_PROPERTY_NAME, localRepo.absolutePath)
            gitKspr.push()

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "E" }
                        commit { title = "C" }
                        commit { title = "one" }
                        commit { title = "B" }
                        commit { title = "A" }
                        commit {
                            title = "two"
                            localRefs += "main"
                        }
                    }
                },
            )

            gitKspr.push()

            // TODO the filter is having some impact on ordering. better if the list was properly ordered regardless
            val remotePrs = gitHub.getPullRequestsById(listOf("E", "C", "one", "B", "A", "two"))

            val prs = remotePrs.map { pullRequest -> pullRequest.baseRefName to pullRequest.headRefName }.toSet()
            val remoteBranchPrefix = remoteBranchPrefix
            val commits = localGit
                .log(JGitClient.HEAD, 6)
                .reversed()
                .windowedPairs()
                .map { (prevCommit, currentCommit) ->
                    val baseRefName = prevCommit
                        ?.let { buildRemoteRef(checkNotNull(it.id), remoteBranchPrefix) }
                        ?: DEFAULT_TARGET_REF
                    val headRefName = buildRemoteRef(checkNotNull(currentCommit.id), remoteBranchPrefix)
                    baseRefName to headRefName
                }
                .toSet()
            assertEquals(commits, prs)
        }
    }

    @Test
    fun `status checks all pass`() {
        withTestSetup(useFakeRemote = false, rollBackChanges = true) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "A"
                        }
                        commit {
                            title = "B"
                        }
                        commit {
                            title = "C"
                            localRefs += "main"
                        }
                    }
                },
            )

            System.setProperty(WORKING_DIR_PROPERTY_NAME, localRepo.absolutePath)
            gitKspr.push()

            // RUN WITH DEBUGGER AND PAUSE HERE UNTIL GH ACTIONS FINISH
            //
            // TODO
            //   The logic to wait for the final status without some sort of callback from the GitHub API is very
            //   tricky, so I'm punting on it for now. Use the debugger and a good old breakpoint on the following line.
            //   Once hit, monitor the GitHub UI and resume once all of the GH actions are finished.
            val status = gitKspr.getStatusString()

            assertEquals(
                """
                    |[+ + + - -] A
                    |[+ + + - -] B
                    |[+ + + - -] C
                """
                    .trimMargin()
                    .toStatusString(),
                status,
            )
        }
    }

    @Test
    fun `status checks middle fails`() {
        withTestSetup(useFakeRemote = false, rollBackChanges = true) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "A"
                        }
                        commit {
                            title = "B"
                            willPassVerification = false
                        }
                        commit {
                            title = "C"
                            localRefs += "main"
                        }
                    }
                },
            )

            System.setProperty(WORKING_DIR_PROPERTY_NAME, localRepo.absolutePath)
            gitKspr.push()

            // RUN WITH DEBUGGER AND PAUSE HERE UNTIL GH ACTIONS FINISH
            //
            // TODO
            //   The logic to wait for the final status without some sort of callback from the GitHub API is very
            //   tricky, so I'm punting on it for now. Use the debugger and a good old breakpoint on the following line.
            //   Once hit, monitor the GitHub UI and resume once all of the GH actions are finished.
            val status = gitKspr.getStatusString()

            assertEquals(
                """
                    |[+ + + - -] A
                    |[+ + - - -] B
                    |[+ + + - -] C
                """
                    .trimMargin()
                    .toStatusString(),
                status,
            )
        }
    }

    @Test
    fun `approve middle`() {
        withTestSetup(useFakeRemote = false, rollBackChanges = true) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                    }
                },
            )

            System.setProperty(WORKING_DIR_PROPERTY_NAME, localRepo.absolutePath)
            gitKspr.push()

            // RUN WITH DEBUGGER AND PAUSE HERE UNTIL GH ACTIONS FINISH
            //
            // TODO
            //   The logic to wait for the final status without some sort of callback from the GitHub API is very
            //   tricky, so I'm punting on it for now. Use the debugger and a good old breakpoint on the following line.
            //   Once hit, monitor the GitHub UI and resume once all of the GH actions are finished.
            val status = gitKspr.getStatusString()

            assertEquals(
                """
                    |[+ + + - -] one
                    |[+ + + + -] two
                    |[+ + + - -] three
                """
                    .trimMargin()
                    .toStatusString(),
                status,
            )
        }
    }
}
