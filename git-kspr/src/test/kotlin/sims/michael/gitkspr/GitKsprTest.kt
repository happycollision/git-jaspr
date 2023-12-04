package sims.michael.gitkspr

import org.junit.jupiter.api.*
import org.slf4j.Logger
import sims.michael.gitkspr.RemoteRefEncoding.buildRemoteRef
import sims.michael.gitkspr.githubtests.GitHubTestHarness
import sims.michael.gitkspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitkspr.githubtests.TestCaseData
import sims.michael.gitkspr.githubtests.generatedtestdsl.testCase
import kotlin.test.assertEquals

interface GitKsprTest {

    val logger: Logger
    val useFakeRemote: Boolean get() = true

    suspend fun GitHubTestHarness.waitForChecksToConclude(
        vararg commitFilter: String,
        timeout: Long = 30_000,
        pollingDelay: Long = 5_000, // Lowering this value too much will result in exhausting rate limits
    )

    suspend fun <T> assertEventuallyEquals(expected: T, getActual: suspend () -> T)

    @Test
    fun `windowedPairs produces expected result`() {
        val input = listOf("one", "two", "three")
        val expected = listOf(null to "one", "one" to "two", "two" to "three")
        val actual = input.windowedPairs()
        assertEquals(expected, actual)
    }

    @Test
    fun `push fails unless workdir is clean`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "some_commit"
                            localRefs += "development"
                        }
                    }
                    localWillBeDirty = true
                },
            )
            val exception = assertThrows<IllegalStateException> {
                gitKspr.push()
            }
            logger.info("Exception message is {}", exception.message)
        }
    }

    @Test
    fun `getRemoteCommitStatuses produces expected result`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "1"
                            localRefs += "development"
                        }
                    }
                },
            )
            gitKspr.push()
            localGit.fetch(DEFAULT_REMOTE_NAME)
            val stack = localGit.getLocalCommitStack(DEFAULT_REMOTE_NAME, DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)
            val remoteCommitStatuses = gitKspr.getRemoteCommitStatuses(stack)
            assertEquals(localGit.log("HEAD", maxCount = 1).single(), remoteCommitStatuses.single().remoteCommit)
        }
    }

    //region status tests
    @Test
    fun `status empty stack`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                            remoteRefs += "main"
                        }
                    }
                },
            )

            assertEquals("Stack is empty.", gitKspr.getAndPrintStatusString())
        }
    }

    @Test
    fun `status stack not pushed`() {
        withTestSetup(useFakeRemote) {
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

            assertEquals(
                """
                    |[- - - - -] one
                    |[- - - - -] two
                    |[- - - - -] three
                """
                    .trimMargin()
                    .toStatusString(),
                gitKspr.getAndPrintStatusString(),
            )
        }
    }

    @Test
    fun `status one commit pushed without PR`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                },
            )

            assertEquals(
                """
                    |[+ - - - -] one
                    |[- - - - -] two
                    |[- - - - -] three
                """
                    .trimMargin()
                    .toStatusString(),
                gitKspr.getAndPrintStatusString(),
            )
        }
    }

    @Test
    fun `status one PR`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                    }
                },
            )

            assertEquals(
                """
                    |[+ + ? - -] one
                    |[- - - - -] two
                    |[- - - - -] three
                """
                    .trimMargin()
                    .toStatusString(),
                gitKspr.getAndPrintStatusString(),
            )
        }
    }

    @Test
    fun `status one PR passing checks`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += buildRemoteRef("one")
                            willPassVerification = true
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                    }
                },
            )

            waitForChecksToConclude("one")

            assertEquals(
                """
                    |[+ + + - -] one
                    |[- - - - -] two
                    |[- - - - -] three
                """
                    .trimMargin()
                    .toStatusString(),
                gitKspr.getAndPrintStatusString(),
            )
        }
    }

    @Test
    fun `status one PR approved`() {
        withTestSetup(useFakeRemote) {
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
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                    }
                },
            )

            waitForChecksToConclude("one")

            assertEquals(
                """
                    |[+ + + + +] one
                    |[+ + + - -] two
                    |[+ + + - -] three
                """
                    .trimMargin()
                    .toStatusString(),
                gitKspr.getAndPrintStatusString(),
            )
        }
    }

    @Test
    fun `status stack one commit behind target`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "in_both_main_and_development"
                            branch {
                                commit {
                                    title = "only_on_main"
                                    remoteRefs += "main"
                                }
                            }
                        }
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
                        willBeApprovedByUserKey = "michael"
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
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            waitForChecksToConclude("one", "two", "three")

            assertEquals(
                """
                    |[+ + + + -] one
                    |[+ + + + -] two
                    |[+ + + + -] three
                    |
                    |Your stack is out-of-date with the base branch (1 commit behind main).
                    |You'll need to rebase it (`git rebase origin/main`) before your stack will be mergeable.
                """
                    .trimMargin()
                    .toStatusString(),
                gitKspr.getAndPrintStatusString(),
            )
        }
    }

    @Test
    fun `status stack two commits behind target`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "in_both_main_and_development"
                            branch {
                                commit {
                                    title = "only_on_main_one"
                                }
                                commit {
                                    title = "only_on_main_two"
                                    remoteRefs += "main"
                                }
                            }
                        }
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
                        willBeApprovedByUserKey = "michael"
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
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            waitForChecksToConclude("one", "two", "three")

            assertEquals(
                """
                    |[+ + + + -] one
                    |[+ + + + -] two
                    |[+ + + + -] three
                    |
                    |Your stack is out-of-date with the base branch (2 commits behind main).
                    |You'll need to rebase it (`git rebase origin/main`) before your stack will be mergeable.
                """
                    .trimMargin()
                    .toStatusString(),
                gitKspr.getAndPrintStatusString(),
            )
        }
    }

    @Test
    fun `status stack check all mergeable`() {
        withTestSetup(useFakeRemote) {
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
                        willBeApprovedByUserKey = "michael"
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
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            waitForChecksToConclude("one", "two", "three")

            assertEquals(
                """
                    |[+ + + + +] one
                    |[+ + + + +] two
                    |[+ + + + +] three
                """
                    .trimMargin()
                    .toStatusString(),
                gitKspr.getAndPrintStatusString(),
            )
        }
    }

    @Test
    fun `status middle commit approved`() {
        withTestSetup(useFakeRemote) {
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

            gitKspr.push()

            waitForChecksToConclude("one", "two", "three")

            assertEventuallyEquals(
                """
                    |[+ + + - -] one
                    |[+ + + + -] two
                    |[+ + + - -] three
                """
                    .trimMargin()
                    .toStatusString(),
                getActual = { gitKspr.getStatusString() },
            )
        }
    }

    @Test
    fun `status middle commit fails`() {
        withTestSetup(useFakeRemote) {
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
                            willPassVerification = false
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
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                    }
                },
            )

            gitKspr.push()

            waitForChecksToConclude("one", "two", "three")

            assertEquals(
                """
                    |[+ + + - -] one
                    |[+ + - - -] two
                    |[+ + + - -] three
                """
                    .trimMargin()
                    .toStatusString(),
                gitKspr.getStatusString(),
            )
        }
    }

    //endregion

    //region push tests
    @Test
    fun `push fetches from remote`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "two"
                            localRefs += "main"
                        }
                        commit {
                            title = "three"
                            remoteRefs += "main"
                        }
                    }
                },
            )

            gitKspr.push()

            assertEquals(
                listOf("three", "two", "one"),
                localGit.log("origin/main", maxCount = 3).map(Commit::shortMessage),
            )
        }
    }

    @TestFactory
    fun `push adds commit IDs`(): List<DynamicTest> {
        data class Test(val name: String, val expected: List<String>, val testCaseData: TestCaseData)
        return listOf(
            Test(
                "all commits missing IDs",
                listOf("0", "1", "2"),
                testCase {
                    repository {
                        commit { title = "0" }
                        commit { title = "1" }
                        commit {
                            title = "2"
                            localRefs += "main"
                        }
                    }
                },
            ),
            Test(
                "only recent commits missing IDs",
                listOf("A", "B", "0", "1", "2"),
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "0" }
                        commit { title = "1" }
                        commit {
                            title = "2"
                            localRefs += "main"
                        }
                    }
                },
            ),
            Test(
                "only commits in the middle missing IDs",
                listOf("A", "B", "0", "1", "2", "C", "D"),
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "0" }
                        commit { title = "1" }
                        commit { title = "2" }
                        commit { title = "C" }
                        commit {
                            title = "D"
                            localRefs += "main"
                        }
                    }
                },
            ),
        ).map { (name, expected, collectCommits) ->
            DynamicTest.dynamicTest(name) {
                withTestSetup {
                    createCommitsFrom(collectCommits)
                    gitKspr.push()
                    assertEquals(
                        expected,
                        localGit.logRange(
                            "${JGitClient.HEAD}~${collectCommits.repository.commits.size}",
                            JGitClient.HEAD,
                        ).map(Commit::id),
                    )
                }
            }
        }
    }

    @Test
    fun `push pushes to expected remote branch names`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "1" }
                        commit { title = "2" }
                        commit {
                            title = "3"
                            localRefs += "main"
                        }
                    }
                },
            )
            gitKspr.push()

            assertEquals(
                (1..3).map { buildRemoteRef(it.toString()) },
                localGit.getRemoteBranches().map(RemoteBranch::name) - DEFAULT_TARGET_REF,
            )
        }
    }

    @Test
    fun `push pushes revision history branches on update`(testInfo: TestInfo) {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "a" }
                        commit { title = "b" }
                        commit {
                            title = "c"
                            localRefs += "main"
                        }
                    }
                },
            )
            gitKspr.push()
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "z" }
                        commit { title = "a" }
                        commit { title = "b" }
                        commit {
                            title = "c"
                            localRefs += "main"
                        }
                    }
                },
            )
            gitKspr.push()
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "y" }
                        commit { title = "a" }
                        commit { title = "b" }
                        commit {
                            title = "c"
                            localRefs += "main"
                        }
                    }
                },
            )
            gitKspr.push()
            gitLogLocalAndRemote()

            assertEquals(
                listOf("a", "a_01", "a_02", "b", "b_01", "b_02", "c", "c_01", "c_02", "y", "z")
                    .map { name -> buildRemoteRef(name) },
                localGit
                    .getRemoteBranches()
                    .map(RemoteBranch::name)
                    .filter { name -> name.startsWith(RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX) }
                    .sorted(),
            )
        }
    }

    @Test
    fun `push updates base refs for any reordered PRs`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "1" }
                        commit { title = "2" }
                        commit { title = "4" }
                        commit {
                            title = "3"
                            localRefs += "development"
                            remoteRefs += "development"
                        }
                    }
                },
            )

            gitKspr.push()

            assertEquals(
                setOf(
                    "kspr/main/1 -> main",
                    "kspr/main/2 -> kspr/main/1",
                    "kspr/main/4 -> kspr/main/2",
                    "kspr/main/3 -> kspr/main/4",
                ),
                gitHub.getPullRequests().map(PullRequest::headToBaseString).toSet(),
            )

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "1" }
                        commit { title = "2" }
                        commit { title = "3" }
                        commit {
                            title = "4"
                            localRefs += "development"
                        }
                    }
                },
            )

            gitKspr.push()

            gitLogLocalAndRemote()

            assertEquals(
                setOf(
                    "kspr/main/1 -> main",
                    "kspr/main/2 -> kspr/main/1",
                    "kspr/main/3 -> kspr/main/2",
                    "kspr/main/4 -> kspr/main/3",
                ),
                gitHub.getPullRequests().map(PullRequest::headToBaseString).toSet(),
            )
        }
    }

    @Test
    fun `push fails when multiple PRs for a given commit ID exist`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("two")
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "One PR"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = "main"
                        title = "Two PR"
                    }
                },
            )
            val exception = assertThrows<GitKspr.SinglePullRequestPerCommitConstraintViolation> {
                gitKspr.push()
            }
            logger.info("Exception message: {}", exception.message)
        }
    }

    @Test
    fun `reorder, drop, add, and re-push`(testInfo: TestInfo) {
        withTestSetup(useFakeRemote) {
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
            val commits = localGit
                .log(JGitClient.HEAD, 6)
                .reversed()
                .windowedPairs()
                .map { (prevCommit, currentCommit) ->
                    val baseRefName = prevCommit
                        ?.let {
                            buildRemoteRef(checkNotNull(it.id), DEFAULT_TARGET_REF)
                        }
                        ?: DEFAULT_TARGET_REF
                    val headRefName = buildRemoteRef(checkNotNull(currentCommit.id), DEFAULT_TARGET_REF)
                    baseRefName to headRefName
                }
                .toSet()
            assertEquals(commits, prs)
        }
    }
    //endregion

    //region merge tests
    @Test
    fun `merge happy path`() {
        withTestSetup(useFakeRemote) {
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
                        willBeApprovedByUserKey = "michael"
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
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            waitForChecksToConclude("one", "two", "three")
            gitKspr.merge(RefSpec("development", "main"))

            assertEquals(
                emptyList(),
                localGit.getLocalCommitStack(DEFAULT_REMOTE_NAME, "development", DEFAULT_TARGET_REF),
            )
        }
    }

    @Test
    fun `merge fails when behind target branch`() {
        withTestSetup(useFakeRemote) {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "in_both_main_and_development"
                            branch {
                                commit {
                                    title = "only_on_main_one"
                                }
                                commit {
                                    title = "only_on_main_two"
                                    remoteRefs += "main"
                                }
                            }
                        }
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
                        willBeApprovedByUserKey = "michael"
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
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            gitKspr.merge(RefSpec("development", "main"))
            assertEquals(
                listOf("one", "two", "three"), // Nothing was merged
                localGit
                    .getLocalCommitStack(DEFAULT_REMOTE_NAME, "development", DEFAULT_TARGET_REF)
                    .map(Commit::shortMessage),
            )
        }
    }

    @Test
    fun `merge pushes latest commit that passes the stack check`() {
        withTestSetup(useFakeRemote) {
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
                        willBeApprovedByUserKey = "michael"
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

            waitForChecksToConclude("one", "two", "three")
            gitKspr.merge(RefSpec("development", "main"))
            assertEquals(
                listOf("three"), // All mergeable commits were merged, leaving "three" as the only one not merged
                localGit
                    .getLocalCommitStack(DEFAULT_REMOTE_NAME, "development", DEFAULT_TARGET_REF)
                    .map(Commit::shortMessage),
            )
        }
    }

    @Test
    fun `merge sets baseRef to targetRef on the latest PR that is mergeable`() {
        withTestSetup(useFakeRemote) {
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
                        }
                        commit {
                            title = "four"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("four")
                        }
                        commit {
                            title = "five"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("five")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
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
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("four")
                        baseRef = buildRemoteRef("three")
                        title = "four"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("five")
                        baseRef = buildRemoteRef("four")
                        title = "five"
                    }
                },
            )

            waitForChecksToConclude("one", "two", "three", "four", "five")

            gitKspr.merge(RefSpec("development", "main"))
            assertEquals(
                DEFAULT_TARGET_REF,
                gitHub.getPullRequestsByHeadRef(buildRemoteRef("four")).last().baseRefName,
            )
        }
    }

    @Test
    fun `merge closes PRs that were rolled up into the PR for the latest mergeable commit`() {
        withTestSetup(useFakeRemote) {
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
                        }
                        commit {
                            title = "four"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("four")
                        }
                        commit {
                            title = "five"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("five")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
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
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("four")
                        baseRef = buildRemoteRef("three")
                        title = "four"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("five")
                        baseRef = buildRemoteRef("four")
                        title = "five"
                    }
                },
            )

            waitForChecksToConclude("one", "two", "three", "four", "five")

            gitKspr.merge(RefSpec("development", "main"))
            assertEventuallyEquals(
                listOf("five"),
                getActual = { gitHub.getPullRequests().map(PullRequest::title) },
            )
        }
    }

    @Test
    fun `merge - none are mergeable`() {
        withTestSetup(useFakeRemote) {
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
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("three")
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
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                    }
                },
            )

            gitKspr.merge(RefSpec("development", "main"))
            assertEquals(
                listOf("one", "two", "three"),
                gitHub.getPullRequests().map(PullRequest::title),
            )
        }
    }

    @Test
    fun `merge with refspec`() {
        withTestSetup(useFakeRemote) {
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
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("three")
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
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
                        willBeApprovedByUserKey = "michael"
                    }
                },
            )

            waitForChecksToConclude("one", "two")
            gitKspr.merge(RefSpec("development^", "main"))
            assertEventuallyEquals(
                listOf("three"),
                getActual = { gitHub.getPullRequests().map(PullRequest::title) },
            )
        }
    }

    //endregion
}

// It may seem silly to repeat what is already defined in GitKspr.HEADER, but if a dev changes the header I want
// these tests to break so that any such changes are very deliberate. This is a compromise between referencing the
// same value from both tests and prod and the other extreme of repeating this header text manually in every test.
fun String.toStatusString() =
    """
            | ┌─ commit is pushed
            | │ ┌─ pull request exists
            | │ │ ┌─ github checks pass
            | │ │ │ ┌── pull request approved
            | │ │ │ │ ┌─── stack check
            | │ │ │ │ │
            |$this

    """.trimMargin()

suspend fun GitKspr.getAndPrintStatusString() = getStatusString().also(::print)
