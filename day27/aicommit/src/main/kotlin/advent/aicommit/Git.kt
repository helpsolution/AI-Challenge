package advent.aicommit

import advent.localllm.commit.StagedChanges
import java.io.File

class GitException(message: String) : RuntimeException(message)

object Git {
    fun stagedChanges(): StagedChanges {
        // Вне репозитория git diff молча уходит в режим --no-index и печатает свою справку — проверяем заранее.
        git("rev-parse", "--git-dir")
        val files = git("diff", "--staged", "--name-only").lines().filter { it.isNotBlank() }
        if (files.isEmpty()) return StagedChanges(emptyList(), "", "")
        return StagedChanges(
            files = files,
            stat = git("diff", "--staged", "--stat=200", "--no-color").trimEnd(),
            diff = git("diff", "--staged", "--no-color", "--no-ext-diff"),
        )
    }

    /** Коммитит с готовым сообщением. С [edit] git сначала откроет его в редакторе, как `git commit -e`. */
    fun commit(message: String, edit: Boolean): Int {
        val file = File.createTempFile("aicommit", ".txt")
        try {
            file.writeText(message)
            val args = listOfNotNull("git", "commit", "-e".takeIf { edit }, "-F", file.absolutePath)
            return ProcessBuilder(args).inheritIO().start().waitFor()
        } finally {
            file.delete()
        }
    }

    // core.quotePath=false — иначе git отдаёт кириллические пути как "\320\236…" и scope не вычислить.
    private fun git(vararg args: String): String {
        val process = ProcessBuilder("git", "-c", "core.quotePath=false", *args).start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GitException(err.trim().ifEmpty { "git ${args.joinToString(" ")} завершился с ошибкой" })
        return out
    }
}
