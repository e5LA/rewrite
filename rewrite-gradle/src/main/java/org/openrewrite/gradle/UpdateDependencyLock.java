/*
 * Copyright 2025 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.maven.tree.GroupArtifactVersion;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.text.PlainText;
import org.openrewrite.text.PlainTextVisitor;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

@Value
@EqualsAndHashCode(callSuper = false)
public class UpdateDependencyLock extends PlainTextVisitor<ExecutionContext> {

    /**
     * There is a shortcoming in  GradleProject model where we don't maintain the intra-project relationships.
     * Ultimately an improvement should be made to GradleProject model, but in the meantime work around it in the recipe
     */
    Set<GradleProject> modules;

    @Override
    public boolean isAcceptable(SourceFile sourceFile, ExecutionContext executionContext) {
        return sourceFile instanceof PlainText && sourceFile.getSourcePath().endsWith("gradle.lockfile");
    }

    @Override
    public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
        if (tree == null) {
            return null;
        }
        Optional<GradleProject> gradleProject = tree.getMarkers().findFirst(GradleProject.class);
        if (!gradleProject.isPresent()) {
            return tree;
        }

        Set<String> lockedConfigurations = new HashSet<>();
        Map<GroupArtifactVersion, SortedSet<String>> lockedVersions = new HashMap<>();
        SortedSet<String> empty = new TreeSet<>();
        List<String> comments = new ArrayList<>();

        PlainText plainText = (PlainText) tree;
        String text = plainText.getText();
        if (StringUtils.isBlank(text)) {
            return plainText;
        }

        GradleProject project = gradleProject.get();

        Arrays.stream(text.split("\n")).forEach(lock -> {
            if (isComment(lock)) {
                comments.add(lock);
            } else {
                lockedConfigurations.addAll(parseLockedConfigurations(lock));
                parseLockedConfigurations(lock)
                        .forEach(lockedConf -> {
                            GradleDependencyConfiguration conf = project.getNameToConfiguration().get(lockedConf);
                            // we do not want to remove locks present in the lockfile which where previously locked but now not present anymore (e.g. by removing a plugin) as that would be a different recipe's task (e.g. CleanupDependencyLock)
                            if (conf == null || !conf.isCanBeResolved()) {
                                GroupArtifactVersion gav = parseLockedArtifact(lock);
                                if (gav != null) {
                                    lockedVersions.computeIfAbsent(gav, k -> new TreeSet<>()).add(lockedConf);
                                }
                            }
                        });
            }
        });
        project.getConfigurations().stream()
                .filter(GradleDependencyConfiguration::isCanBeResolved)
                .filter(configuration -> lockedConfigurations.contains(configuration.getName()))
                .forEach(conf -> {
                    List<ResolvedDependency> transitivelyResolvedDependencies = conf.getResolved();
                    String configuration = conf.getName();
                    if (transitivelyResolvedDependencies.isEmpty()) {
                        empty.add(configuration);
                    } else {
                        boolean anyChanged = false;
                        for (ResolvedDependency resolved : transitivelyResolvedDependencies) {
                            if (isDependencyOnAnotherModule(resolved, project)) {
                                continue;
                            }
                            lockedVersions.computeIfAbsent(resolved.getGav().asGroupArtifactVersion(), k -> new TreeSet<>()).add(configuration);
                            anyChanged = true;
                        }
                        if (!anyChanged) {
                            empty.add(configuration);
                        }
                    }
                });

        SortedSet<String> locks = new TreeSet<>();
        lockedVersions.forEach((gav, confs) -> {
            String lock = asLock(gav, confs);
            if (lock != null) {
                locks.add(lock);
            }
        });

        StringBuilder sb = new StringBuilder();
        if (!comments.isEmpty()) {
            sb.append(String.join("\n", comments));
        }
        if (!comments.isEmpty() && !locks.isEmpty()) {
            sb.append("\n");
        }
        if (!locks.isEmpty()) {
            sb.append(String.join("\n", locks));
        }
        sb.append("\n").append(asLock(null, empty));
        if (text.endsWith("\n")) {
            sb.append("\n");
        }

        if (plainText.getText().contentEquals(sb)) {
            return tree;
        }
        return plainText.withText(sb.toString()).withMarkers(tree.getMarkers().setByType(project));
    }

    private @Nullable String asLock(@Nullable GroupArtifactVersion gav, @Nullable Set<String> configurations) {
        TreeSet<String> sortedConfigurations = new TreeSet<>(configurations == null ? emptyList() : configurations);
        if (gav == null) {
            return "empty=" + String.join(",", sortedConfigurations);
        }
        if (sortedConfigurations.isEmpty()) {
            return null;
        }
        return String.format("%s=%s", gav, String.join(",", sortedConfigurations));
    }

    private static boolean isComment(String entry) {
        return entry.startsWith("# ");
    }

    private boolean isDependencyOnAnotherModule(ResolvedDependency resolved, GradleProject project) {
        if (Objects.equals(project.getGroup(), resolved.getGroupId())) {
            return modules.stream().anyMatch(module -> resolved.getArtifactId().equals(module.getName()));
        }
        return false;
    }

    private @Nullable GroupArtifactVersion parseLockedArtifact(String lock) {
        String[] parts = lock.split("=");
        if (parts.length != 2) {
            return null;
        }
        String[] gavParts = parts[0].split(":");
        if (gavParts.length != 3) {
            return null;
        }
        return new GroupArtifactVersion(gavParts[0], gavParts[1], gavParts[2]);
    }

    private Set<String> parseLockedConfigurations(String lock) {
        String[] parts = lock.split("=");
        if (parts.length != 2) {
            return emptySet();
        }
        return Arrays.stream(parts[1].split(","))
                .map(String::trim)
                .filter(conf -> !conf.isEmpty())
                .collect(Collectors.toSet());
    }
}
