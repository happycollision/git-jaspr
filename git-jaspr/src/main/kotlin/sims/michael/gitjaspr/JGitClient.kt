package sims.michael.gitjaspr

import org.eclipse.jgit.api.CheckoutResult
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.RemoteRefUpdate.Status
import org.slf4j.LoggerFactory
import sims.michael.gitjaspr.CommitFooters.addFooters
import sims.michael.gitjaspr.CommitFooters.getFooters
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX
import sims.michael.gitjaspr.RemoteRefEncoding.getCommitIdFromRemoteRef
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.eclipse.jgit.transport.RefSpec as JRefSpec

// TODO consider extracting an interface from this once the implementation settles
class JGitClient(val workingDirectory: File, val remoteBranchPrefix: String = DEFAULT_REMOTE_BRANCH_PREFIX) {
    private val logger = LoggerFactory.getLogger(JGitClient::class.java)

    fun getParents(commit: Commit): List<Commit> = useGit { git ->
        logger.trace("getParents {}", commit)
        git
            .log()
            .add(git.repository.resolve(commit.hash))
            .setMaxCount(1)
            .call()
            .single()
            .parents
            .map { it.toCommit(git) }
    }

    fun log(revision: String, maxCount: Int = -1): List<Commit> = useGit { git ->
        logger.trace("log {} {}", revision, maxCount)
        git
            .log()
            .add(git.repository.resolve(revision))
            .setMaxCount(maxCount)
            .call()
            .toList()
            .map { revCommit -> revCommit.toCommit(git) }
    }

    fun logRange(since: String, until: String): List<Commit> = useGit { git ->
        logger.trace("logRange {}..{}", since, until)
        val r = git.repository
        val sinceObjectId = checkNotNull(r.resolve(since)) { "$since doesn't exist" }
        val untilObjectId = checkNotNull(r.resolve(until)) { "$until doesn't exist" }
        val commits = git.log().addRange(sinceObjectId, untilObjectId).call().toList()
        commits.map { revCommit -> revCommit.toCommit(git) }.reversed()
    }

    fun log(): List<Commit> {
        logger.trace("log")
        return useGit { git -> git.log().call().map { it.toCommit(git) }.reversed() }
    }

    fun logAll(): List<Commit> {
        logger.trace("logAll")
        return useGit { git -> git.log().all().call().map { it.toCommit(git) }.reversed() }
    }

    fun isWorkingDirectoryClean(): Boolean {
        logger.trace("isWorkingDirectoryClean")
        return useGit { git -> git.status().call().isClean }
    }

    fun getLocalCommitStack(remoteName: String, localObjectName: String, targetRefName: String): List<Commit> {
        logger.trace("getLocalCommitStack {} {} {}", remoteName, localObjectName, targetRefName)
        return useGit { git ->
            val r = git.repository
            val trackingBranch = requireNotNull(r.resolve("$remoteName/$targetRefName")) {
                "$targetRefName does not exist in the remote"
            }
            val revCommits = git
                .log()
                .addRange(trackingBranch, r.resolve(localObjectName))
                .call()
                .toList()
            val mergeCommits = revCommits.filter { it.parentCount > 1 }
            val objectReader = r.newObjectReader()
            require(mergeCommits.isEmpty()) {
                "Merge commits are not supported ${mergeCommits.map { objectReader.abbreviate(it.id).name() }}"
            }
            revCommits.map { revCommit -> revCommit.toCommit(git) }.reversed()
        }
    }

    private fun refExists(ref: String): Boolean {
        logger.trace("refExists {}", ref)
        return useGit { git -> git.repository.resolve(ref) != null }
    }

    fun getBranchNames(): List<String> {
        logger.trace("getBranchNames")
        return useGit { git ->
            git
                .branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .call()
                .map {
                    it.name.removePrefix(Constants.R_HEADS)
                }
        }
    }

    fun getRemoteBranches(): List<RemoteBranch> {
        logger.trace("getRemoteBranches")
        return useGit { git ->
            git
                .branchList()
                .setListMode(ListBranchCommand.ListMode.REMOTE)
                .call()
                .filter { it.name.startsWith(Constants.R_REMOTES) }
                .map { ref ->
                    val r = git.repository
                    val shortBranchName = checkNotNull(r.shortenRemoteBranchName(ref.name)) {
                        "Short branch name was null for ${ref.name}"
                    }
                    RemoteBranch(shortBranchName, r.parseCommit(ref.objectId).toCommit(git))
                }
        }
    }

    fun getRemoteBranchesById(): Map<String, RemoteBranch> {
        logger.trace("getRemoteBranchesById")
        return getRemoteBranches()
            .mapNotNull { branch ->
                val commitId = getCommitIdFromRemoteRef(branch.name, remoteBranchPrefix)
                if (commitId != null) commitId to branch else null
            }
            .toMap()
    }

    fun fetch(remoteName: String) {
        logger.trace("fetch {}", remoteName)
        useGit { git -> git.fetch().setRemote(remoteName).call() }
    }

    fun reset(refName: String) = apply {
        logger.trace("reset {}", refName)
        useGit { git ->
            git.reset().setRef(git.repository.resolve(refName).name).setMode(ResetCommand.ResetType.HARD).call()
        }
    }

    fun checkout(refName: String) = apply {
        logger.trace("checkout {}", refName)
        useGit { git ->
            val refExists = refExists(refName)
            require(refExists) { "$refName does not exist" }
            git.checkout().setName(refName).run {
                call()
                check(result.status == CheckoutResult.Status.OK) { "Checkout result was ${result.status}" }
            }
        }
    }

    fun branch(name: String, startPoint: String = "HEAD", force: Boolean = false): Commit? {
        logger.trace("branch {} start {} force {}", name, startPoint, force)
        val old = if (refExists(name)) log(name, maxCount = 1).single() else null
        useGit { git -> git.branchCreate().setName(name).setForce(force).setStartPoint(startPoint).call() }
        return old
    }

    fun deleteBranches(names: List<String>, force: Boolean = false): List<String> {
        logger.trace("deleteBranches {} {}", names, force)
        return useGit { git ->
            git.branchDelete().setBranchNames(*names.toTypedArray()).setForce(force).call()
        }
    }

    fun init(): JGitClient {
        logger.trace("init")
        return apply {
            Git.init().setDirectory(workingDirectory).setInitialBranch("main").call().close()
        }
    }

    fun add(filePattern: String): JGitClient {
        logger.trace("add {}", filePattern)
        return apply {
            useGit { git ->
                git.add().addFilepattern(filePattern).call()
            }
        }
    }

    fun commit(message: String, footerLines: Map<String, String> = emptyMap()): Commit {
        logger.trace("commit {} {}", message, footerLines)
        return useGit { git ->
            val committer = PersonIdent(PersonIdent(git.repository), Instant.now())
            git.commit().setMessage(addFooters(message, footerLines)).setCommitter(committer).call().toCommit(git)
        }
    }

    fun setCommitId(commitId: String) {
        logger.trace("setCommitId {}", commitId)
        useGit { git ->
            val r = git.repository
            val head = r.parseCommit(r.findRef(HEAD).objectId)
            require(!getFooters(head.fullMessage).containsKey(COMMIT_ID_LABEL))
            git
                .commit()
                .setAmend(true)
                .setMessage(addFooters(head.fullMessage, mapOf(COMMIT_ID_LABEL to commitId)))
                .call()
        }
    }

    fun clone(uri: String): JGitClient {
        logger.trace("clone {}", uri)
        return apply {
            Git.cloneRepository().setDirectory(workingDirectory).setURI(uri).call().close()
        }
    }

    fun cherryPick(commit: Commit): Commit {
        logger.trace("cherryPick {}", commit)
        return useGit { git ->
            git.cherryPick().include(git.repository.resolve(commit.hash)).call().newHead.toCommit(git)
            // TODO check results and things
        }
    }

    fun push(refSpecs: List<RefSpec>) {
        logger.trace("push {}", refSpecs)
        if (refSpecs.isNotEmpty()) {
            useGit { git ->
                val specs = refSpecs.map { (localRef, remoteRef) ->
                    JRefSpec("$localRef:$R_HEADS$remoteRef")
                }
                checkNoPushErrors(
                    git
                        .push()
                        .setAtomic(true)
                        .setRefSpecs(specs)
                        .call(),
                )
            }
        }
    }

    private fun checkNoPushErrors(pushResults: Iterable<PushResult>) {
        val pushErrors = pushResults
            .flatMap { result -> result.remoteUpdates }
            .filterNot { it.status in SUCCESSFUL_PUSH_STATUSES }
        for (e in pushErrors) {
            logger.error("Push failed: {} -> {} ({}: {})", e.srcRef, e.remoteName, e.message, e.status)
        }
        check(pushErrors.isEmpty()) { "A git push operation failed, please check the logs" }
    }

    fun getRemoteUriOrNull(remoteName: String): String? = useGit { git ->
        git.remoteList().call().singleOrNull { it.name == remoteName }?.urIs?.firstOrNull()?.toASCIIString()
    }

    private inline fun <T> useGit(block: (Git) -> T): T = Git.open(workingDirectory).use(block)

    private fun RevCommit.toCommit(git: Git): Commit {
        val r = git.repository
        val objectReader = r.newObjectReader()
        return Commit(
            objectReader.abbreviate(id).name(),
            shortMessage,
            fullMessage,
            getFooters(fullMessage)[COMMIT_ID_LABEL],
            Ident(committerIdent.name, committerIdent.emailAddress),
            ZonedDateTime.ofInstant(committerIdent.`when`.toInstant(), ZoneId.systemDefault()),
            ZonedDateTime.ofInstant(authorIdent.`when`.toInstant(), ZoneId.systemDefault()),
        )
    }

    companion object {
        const val HEAD = Constants.HEAD
        const val R_HEADS = Constants.R_HEADS
        const val R_REMOTES = Constants.R_REMOTES
        private val SUCCESSFUL_PUSH_STATUSES = setOf(Status.OK, Status.UP_TO_DATE, Status.NON_EXISTING)
    }
}
