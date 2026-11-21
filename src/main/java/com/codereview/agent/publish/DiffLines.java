package com.codereview.agent.publish;

import com.codereview.agent.review.Comment;
import com.codereview.agent.triage.diff.FileChange;
import com.codereview.agent.triage.diff.RepositoryChangeSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The lines a review may comment on inline.
 *
 * <p>GitHub only accepts an inline comment on a line that the pull request actually changed, so
 * the pipeline's own diff is reused here rather than guessed from the report. Comments outside it
 * fall back into the review body instead of being lost.
 */
public final class DiffLines {

    private DiffLines() {
    }

    public static Set<String> addedLines(Path repository, String base, String head) throws IOException {
        Set<String> lines = new HashSet<>();
        List<FileChange> changes = new RepositoryChangeSource().changes(repository, base, head);
        for (FileChange change : changes) {
            for (FileChange.EditRange edit : change.edits()) {
                if (edit.newStart() < 1 || edit.newEnd() < edit.newStart()) {
                    continue;
                }
                for (int line = edit.newStart(); line <= edit.newEnd(); line++) {
                    lines.add(change.displayPath() + ":" + line);
                }
            }
        }
        return lines;
    }

    public static List<Comment> inside(List<Comment> comments, Set<String> addedLines) {
        return comments.stream()
                .filter(comment -> addedLines.contains(comment.file() + ":" + comment.line()))
                .toList();
    }
}
