package com.codereview.agent.triage.diff;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over a real repository. The point is not JGit itself but the conversion
 * contract: 0-based half-open edits from JGit become 1-based inclusive ranges, and both
 * revisions of the file text survive intact.
 */
class RepositoryChangeSourceTest {

    private static Path scratch() throws Exception {
        Path root = Path.of("target", "test-repos");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "repo");
    }

    @Test
    void reportsAModifiedFileWithBothRevisions() throws Exception {
        Path repo = scratch();
        Files.writeString(repo.resolve("A.java"), """
                class A {
                    void m() {
                        a();
                    }
                }
                """);

        String base;
        String head;
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("first").setAuthor("t", "t@example.com").call();
            base = ObjectId.toString(git.getRepository().resolve("HEAD"));

            Files.writeString(repo.resolve("A.java"), """
                    class A {
                        void m() {
                            b();
                        }
                    }
                    """);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("second").setAuthor("t", "t@example.com").call();
            head = ObjectId.toString(git.getRepository().resolve("HEAD"));
        }

        List<FileChange> changes = new RepositoryChangeSource().changes(repo, base, head);

        assertThat(changes).hasSize(1);
        FileChange change = changes.get(0);
        assertThat(change.displayPath()).isEqualTo("A.java");
        assertThat(change.type()).isEqualTo(FileChange.ChangeType.MODIFIED);
        assertThat(change.oldText()).contains("a();");
        assertThat(change.newText()).contains("b();");
        assertThat(change.edits()).isNotEmpty();
        FileChange.EditRange edit = change.edits().get(0);
        assertThat(edit.hasOldLines()).isTrue();
        assertThat(edit.hasNewLines()).isTrue();
        assertThat(edit.removedLines()).isEqualTo(1);
        assertThat(edit.addedLines()).isEqualTo(1);
    }

    @Test
    void reportsAnAddedFileWithNoOldText() throws Exception {
        Path repo = scratch();
        Files.writeString(repo.resolve("A.java"), "class A { }\n");

        String base;
        String head;
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("first").setAuthor("t", "t@example.com").call();
            base = ObjectId.toString(git.getRepository().resolve("HEAD"));

            Files.writeString(repo.resolve("B.java"), "class B { void n() { } }\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("second").setAuthor("t", "t@example.com").call();
            head = ObjectId.toString(git.getRepository().resolve("HEAD"));
        }

        List<FileChange> changes = new RepositoryChangeSource().changes(repo, base, head);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).type()).isEqualTo(FileChange.ChangeType.ADDED);
        assertThat(changes.get(0).oldText()).isNull();
        assertThat(changes.get(0).newText()).contains("class B");
    }
}
