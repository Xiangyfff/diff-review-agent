package com.codereview.agent.investigate.tool;

import com.codereview.agent.investigate.RepoAccess;
import com.codereview.agent.investigate.ReviewTool;
import com.codereview.agent.investigate.ToolCall;
import com.codereview.agent.investigate.ToolOutcome;

import java.util.List;

/**
 * Reads a line range of one file.
 *
 * <p>The range is clamped rather than rejected: a model that asks for lines 400-460 of a 380-line
 * file should get the tail of the file and a note, not a dead end that burns a turn.
 */
public final class ReadLinesTool implements ReviewTool {

    private final RepoAccess repository;

    public ReadLinesTool(RepoAccess repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return "read_lines";
    }

    @Override
    public String purpose() {
        return "Read a range of lines from one file of the repository under review.";
    }

    @Override
    public String arguments() {
        return "path (repository-relative file path), start (first line, 1-based), end (last line)";
    }

    @Override
    public ToolOutcome run(ToolCall call) {
        String path = call.argument("path");
        if (path.isEmpty()) {
            return ToolOutcome.failed("path is required");
        }
        if (!repository.exists(path)) {
            return ToolOutcome.failed("no such file inside the repository: " + path);
        }
        int start = call.intArgument("start", 1);
        int end = call.intArgument("end", start + 40);
        RepoAccess.Text text = repository.read(path, start, end);
        if (text.text().isEmpty()) {
            return ToolOutcome.failed("line " + start + " is past the end of " + path);
        }
        return ToolOutcome.of("lines " + text.startLine() + "-" + text.endLine() + " of " + path,
                List.of(new ToolOutcome.Excerpt(text.file(), text.startLine(), text.endLine(), text.text())));
    }
}
