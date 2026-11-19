package com.codereview.agent.triage.diff;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the changes between two revisions of a repository.
 *
 * <p>Diff parsing is delegated to JGit rather than written by hand. Rename detection,
 * binary detection and hunk boundaries are exactly the places where hand-written text
 * differencing produces subtly wrong line numbers, and every later stage depends on those
 * line numbers being right.
 */
public final class RepositoryChangeSource {

    /** Files above this size are skipped: reviewing a generated or vendored monster is not the job. */
    private static final long MAX_FILE_BYTES = 1_000_000L;

    public List<FileChange> changes(Path repositoryPath, String baseRevision, String headRevision) throws IOException {
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(repositoryPath.resolve(".git").toFile())
                .readEnvironment()
                .findGitDir()
                .build();
             Git git = new Git(repository);
             RevWalk walk = new RevWalk(repository);
             DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

            ObjectId baseCommit = repository.resolve(baseRevision);
            ObjectId headCommit = repository.resolve(headRevision);
            if (baseCommit == null || headCommit == null) {
                throw new IllegalArgumentException("unknown revision: "
                        + (baseCommit == null ? baseRevision : headRevision));
            }

            formatter.setRepository(repository);
            formatter.setDetectRenames(true);

            CanonicalTreeParser oldTree = treeParser(repository, walk, baseCommit);
            CanonicalTreeParser newTree = treeParser(repository, walk, headCommit);
            List<DiffEntry> entries = formatter.scan(oldTree, newTree);

            List<FileChange> changes = new ArrayList<>();
            try (ObjectReader reader = repository.newObjectReader()) {
                for (DiffEntry entry : entries) {
                    FileChange change = toFileChange(entry, formatter, reader);
                    if (change != null) {
                        changes.add(change);
                    }
                }
            }
            return changes;
        }
    }

    private static CanonicalTreeParser treeParser(Repository repository, RevWalk walk, ObjectId commitId) throws IOException {
        RevTree tree = walk.parseTree(commitId);
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (ObjectReader reader = repository.newObjectReader()) {
            parser.reset(reader, tree);
        }
        return parser;
    }

    private static FileChange toFileChange(DiffEntry entry, DiffFormatter formatter, ObjectReader reader) throws IOException {
        if (entry.getOldMode() == org.eclipse.jgit.lib.FileMode.GITLINK
                || entry.getNewMode() == org.eclipse.jgit.lib.FileMode.GITLINK) {
            return null;
        }
        String oldPath = DiffEntry.DEV_NULL.equals(entry.getOldPath()) ? null : entry.getOldPath();
        String newPath = DiffEntry.DEV_NULL.equals(entry.getNewPath()) ? null : entry.getNewPath();

        List<FileChange.EditRange> edits = new ArrayList<>();
        for (Edit edit : formatter.toFileHeader(entry).toEditList()) {
            edits.add(FileChange.EditRange.fromZeroBasedHalfOpen(
                    edit.getBeginA(), edit.getEndA(), edit.getBeginB(), edit.getEndB()));
        }

        String oldText = oldPath == null ? null : readText(reader, entry.getOldId().toObjectId());
        String newText = newPath == null ? null : readText(reader, entry.getNewId().toObjectId());

        return new FileChange(oldPath, newPath, changeTypeOf(entry), edits, oldText, newText);
    }

    private static FileChange.ChangeType changeTypeOf(DiffEntry entry) {
        return switch (entry.getChangeType()) {
            case ADD -> FileChange.ChangeType.ADDED;
            case DELETE -> FileChange.ChangeType.DELETED;
            case MODIFY -> FileChange.ChangeType.MODIFIED;
            case RENAME -> FileChange.ChangeType.RENAMED;
            case COPY -> FileChange.ChangeType.COPIED;
        };
    }

    /** Returns null for content that is too large or that is not text. */
    private static String readText(ObjectReader reader, ObjectId id) throws IOException {
        if (id == null || ObjectId.zeroId().equals(id)) {
            return null;
        }
        ObjectLoader loader = reader.open(id);
        if (loader.getSize() > MAX_FILE_BYTES) {
            return null;
        }
        byte[] bytes = loader.getBytes();
        if (looksBinary(bytes)) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
