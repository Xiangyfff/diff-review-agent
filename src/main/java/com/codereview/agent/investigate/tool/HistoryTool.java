package com.codereview.agent.investigate.tool;

import com.codereview.agent.investigate.RepoAccess;
import com.codereview.agent.investigate.ReviewTool;
import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.List;

/**
 * Shows which commit last touched a range of lines.
 *
 * <p>Reviewers ask "is this line new" all the time, and a commit message often explains why a
 * removal was safe. The answer only covers committed history: lines that exist only in the working
 * tree come back as unattributed, and the summary says so.
 */
public final class HistoryTool implements ReviewTool {

    private static final int MAX_LINES = 30;

    private final RepoAccess repository;

    public HistoryTool(RepoAccess repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return "history";
    }

    @Override
    public String purpose() {
        return "Show the commits that last touched a range of lines in one file (committed history only).";
    }

    @Override
    public String arguments() {
        return "path (repository-relative), start (first line, 1-based), end (last line)";
    }

    @Override
    public ToolOutcome run(ToolCall call) {
        String path = call.argument("path");
        if (path.isEmpty() || !repository.exists(path)) {
            return ToolOutcome.failed("no such file inside the repository: " + path);
        }
        int start = Math.max(1, call.intArgument("start", 1));
        int end = Math.max(start, call.intArgument("end", start + 5));
        if (end - start + 1 > MAX_LINES) {
            end = start + MAX_LINES - 1;
        }
        try (Git git = Git.open(repository.root().toFile())) {
            BlameResult blame = git.blame()
                    .setFilePath(path)
                    .setFollowFileRenames(true)
                    .call();
            if (blame == null || blame.getResultContents() == null) {
                return ToolOutcome.failed("this repository has no committed history for " + path);
            }
            StringBuilder text = new StringBuilder();
            int last = Math.min(end, blame.getResultContents().size());
            for (int line = start; line <= last; line++) {
                RevCommit commit = blame.getSourceCommit(line - 1);
                text.append(line).append(": ");
                if (commit == null) {
                    text.append("(uncommitted)");
                } else {
                    text.append(commit.abbreviate(8).name()).append(' ').append(commit.getShortMessage());
                }
                text.append('\n');
            }
            return ToolOutcome.of("history of " + path + " lines " + start + "-" + last,
                    List.of(new ToolOutcome.Excerpt(path, start, last, text.toString().stripTrailing())));
        } catch (Exception e) {
            return ToolOutcome.failed("cannot read git history: " + e.getClass().getSimpleName());
        }
    }
}
