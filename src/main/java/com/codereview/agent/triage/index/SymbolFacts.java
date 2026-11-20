package com.codereview.agent.triage.index;

/**
 * What the repository-wide index knows about one symbol.
 *
 * <p>These are facts a person could verify by grepping the repository, not verdicts. They are
 * deliberately coarse: the index matches names, knows nothing about overloads or types, and
 * therefore reports something that is a hint for ordering rather than a reason to investigate.
 *
 * @param symbol           the simple name that was looked up
 * @param mentioningFiles  number of source files where the name appears at all, including the
 *                         file that declares it
 * @param otherFiles       number of files mentioning the name besides the declaring file, so a
 *                         symbol used nowhere else is zero
 * @param declared         whether a declaration for the name was seen while indexing
 * @param publiclyDeclared whether that declaration is public
 * @param declarationCount how many declarations share this simple name; more than one means the
 *                         visibility above is merely the first one seen and must not be relied on
 */
public record SymbolFacts(
        String symbol,
        int mentioningFiles,
        int otherFiles,
        boolean declared,
        boolean publiclyDeclared,
        int declarationCount) {

    public boolean isEmpty() {
        return !declared && mentioningFiles == 0;
    }

    /**
     * One line for the prompt. It states the numbers and what they do not mean, because a model
     * that takes "referenced by seven files" as evidence of danger will invent dangers.
     */
    public String describe() {
        StringBuilder line = new StringBuilder();
        line.append("符号 ").append(symbol).append(" 在另外 ").append(otherFiles)
                .append(" 个源文件里出现过（共 ").append(mentioningFiles).append(" 个文件）");
        if (!declared) {
            line.append("；没有在索引中找到它的声明，可能是重名或外部依赖里的符号");
        } else if (publiclyDeclared) {
            line.append("；声明是 public，改动可能影响到本仓库之外的调用方");
        } else {
            line.append("；声明不是 public，影响范围限于本仓库");
        }
        if (declarationCount > 1) {
            line.append("；同名的声明有 ").append(declarationCount).append(" 处，可见性以其中一处为准");
        }
        line.append("。这是按名字统计的粗事实，不区分重载，也不能证明这些位置真的调用了它。");
        return line.toString();
    }
}
