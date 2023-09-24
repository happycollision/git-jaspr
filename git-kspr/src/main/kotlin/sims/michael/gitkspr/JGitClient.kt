package sims.michael.gitkspr

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.slf4j.LoggerFactory
import java.io.File
import org.eclipse.jgit.transport.RefSpec as JRefSpec

// TODO consider extracting an interface from this once the implementation settles
class JGitClient(private val workingDirectory: File) {
    private val logger = LoggerFactory.getLogger(JGitClient::class.java)
    fun getLocalCommitStack(remoteName: String, localObjectName: String, targetRefName: String): List<Commit> {
        logger.trace("getLocalCommitStack {} {} {}", remoteName, localObjectName, targetRefName)
        return useGit { git ->
            git.fetch().setRemote(remoteName).call()
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
            revCommits
                .map { commit ->
                    Commit(
                        objectReader.abbreviate(commit.id).name(),
                        commit.shortMessage,
                        commit.getFooterLines(COMMIT_ID_LABEL).firstOrNull(),
                    )
                }
                .reversed()
        }
    }

    fun fetch(remoteName: String) {
        logger.trace("fetch {}", remoteName)
        useGit { git -> git.fetch().setRemote(remoteName).call() }
    }

    fun checkout(refName: String, createBranch: Boolean = false) = apply {
        logger.trace("checkout {}{}", refName, if (createBranch) " (create)" else "")
        useGit { git ->
            val name = if (createBranch) refName else git.repository.resolve(refName).name
            git.checkout().setName(name).setCreateBranch(createBranch).call()
        }
    }

    fun init(): JGitClient = apply {
        Git.init().setDirectory(workingDirectory).setInitialBranch("main").call().close()
    }

    fun add(filePattern: String) = apply {
        useGit { git ->
            git.add().addFilepattern(filePattern).call()
        }
    }

    fun commit(message: String) {
        useGit { git ->
            git.commit().setMessage(message).call()
        }
    }

    fun setCommitId(commitId: String) {
        logger.trace("setCommitId {}", commitId)
        useGit { git ->
            val r = git.repository
            val head = r.parseCommit(r.findRef(Constants.HEAD).objectId)
            require(head.getFooterLines(COMMIT_ID_LABEL).isEmpty())
            git.commit().setAmend(true).setMessage(appendCommitId(head.fullMessage, commitId)).call()
        }
    }

    fun clone(uri: String) = apply {
        Git.cloneRepository().setDirectory(workingDirectory).setURI(uri).call().close()
    }

    private fun appendCommitId(fullMessage: String, commitId: String) =
        """
            $fullMessage

            $COMMIT_ID_LABEL: $commitId

        """.trimIndent()

    fun cherryPick(commit: Commit) {
        logger.trace("cherryPick {}", commit)
        useGit { git ->
            git.cherryPick().include(git.repository.resolve(commit.hash)).call()
            // TODO check results and things
        }
    }

    fun push(refSpecs: List<RefSpec>) {
        logger.trace("push {}", refSpecs)
        useGit { git ->
            val specs = refSpecs.map { (localRef, remoteRef) ->
                JRefSpec("$localRef:${Constants.R_HEADS}$remoteRef")
            }
            git
                .push()
                .setForce(true)
                .setAtomic(true)
                .setRefSpecs(specs)
                .call()
        }
    }

    fun getRemoteUriOrNull(remoteName: String): String? = useGit { git ->
        git.remoteList().call().singleOrNull { it.name == remoteName }?.urIs?.firstOrNull()?.toASCIIString()
    }

    private inline fun <T> useGit(block: (Git) -> T): T = Git.open(workingDirectory).use(block)

    companion object {
        private const val COMMIT_ID_LABEL = "commit-id"
    }
}
