package com.intellij.ml.llm.agents.frontend.compose.ui.components.utils;

import com.intellij.execution.wsl.WSLDistribution;
import com.intellij.execution.wsl.WslDistributionManager;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class MarkdownWslLinkPatchSupport {
    private MarkdownWslLinkPatchSupport() {}

    public static String normalizeLocalFilePathCandidate(String url, String localFilePath) {
        if (!isWindowsHost()) {
            return localFilePath;
        }

        if (normalizeUnixPath(url) != null) {
            return null;
        }

        if (localFilePath != null) {
            String normalizedCandidate = localFilePath.trim().replace('\\', '/');
            if (isUnixAbsolute(normalizedCandidate)) {
                return null;
            }
        }

        return localFilePath;
    }

    public static void openPathOrUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }

        if (!isWindowsHost() || !tryOpenUnixPath(url)) {
            BrowserUtil.open(url);
        }
    }

    private static boolean tryOpenUnixPath(String url) {
        String linuxPath = normalizeUnixPath(url);
        if (linuxPath == null) {
            return false;
        }

        int line = -1;
        int column = -1;
        int lastSlash = linuxPath.lastIndexOf('/');
        int lastColon = linuxPath.lastIndexOf(':');
        if (lastColon > lastSlash) {
            String tail = linuxPath.substring(lastColon + 1);
            if (isPositiveInt(tail)) {
                column = Integer.parseInt(tail);
                linuxPath = linuxPath.substring(0, lastColon);

                int secondColon = linuxPath.lastIndexOf(':');
                if (secondColon > lastSlash && isPositiveInt(linuxPath.substring(secondColon + 1))) {
                    line = Integer.parseInt(linuxPath.substring(secondColon + 1));
                    linuxPath = linuxPath.substring(0, secondColon);
                } else {
                    line = column;
                    column = -1;
                }
            }
        }

        Project project = findBestProject();
        String windowsPath = resolveWindowsPath(linuxPath, project);
        if (windowsPath == null || windowsPath.isBlank()) {
            return false;
        }

        Path nioPath;
        try {
            nioPath = Path.of(windowsPath);
        } catch (Throwable ignored) {
            return false;
        }

        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(nioPath);
        if (file == null) {
            file = LocalFileSystem.getInstance().findFileByNioFile(nioPath);
        }
        if (file == null) {
            return false;
        }

        if (project == null) {
            project = findBestProject();
        }
        if (project == null) {
            return false;
        }

        OpenFileDescriptor descriptor = line > 0
            ? new OpenFileDescriptor(project, file, Math.max(line - 1, 0), Math.max(column - 1, 0))
            : new OpenFileDescriptor(project, file);
        descriptor.navigate(true);
        return true;
    }

    private static String normalizeUnixPath(String url) {
        String normalized = url.trim().replace('\\', '/');
        if (normalized.startsWith("file:")) {
            String path;
            try {
                path = java.net.URI.create(normalized).getPath();
            } catch (Throwable ignored) {
                return null;
            }
            return isUnixAbsolute(path) ? path : null;
        }

        return isUnixAbsolute(normalized) ? normalized : null;
    }

    private static String resolveWindowsPath(String linuxPath, Project project) {
        WSLDistribution preferred = findProjectDistribution(project);
        String fallback = tryConvert(preferred, linuxPath, false);
        String resolved = tryConvert(preferred, linuxPath, true);
        if (resolved != null) {
            return resolved;
        }

        List<WSLDistribution> distributions = WslDistributionManager.getInstance().getInstalledDistributions();
        for (WSLDistribution distribution : distributions) {
            if (preferred != null && preferred.getMsId().equalsIgnoreCase(distribution.getMsId())) {
                continue;
            }
            resolved = tryConvert(distribution, linuxPath, true);
            if (resolved != null) {
                return resolved;
            }
        }

        return fallback;
    }

    private static String tryConvert(WSLDistribution distribution, String linuxPath, boolean requireExistingFile) {
        if (distribution == null) {
            return null;
        }

        try {
            String windowsPath = distribution.getWindowsPath(linuxPath);
            if (windowsPath == null || windowsPath.isBlank()) {
                return null;
            }
            if (!requireExistingFile) {
                return windowsPath;
            }

            Path nioPath = Path.of(windowsPath);
            return Files.exists(nioPath) ? windowsPath : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static WSLDistribution findProjectDistribution(Project project) {
        String distributionId = extractDistributionId(project);
        if (distributionId == null || distributionId.isBlank()) {
            return null;
        }

        List<WSLDistribution> distributions = WslDistributionManager.getInstance().getInstalledDistributions();
        for (WSLDistribution distribution : distributions) {
            if (distributionId.equalsIgnoreCase(distribution.getMsId())) {
                return distribution;
            }
        }
        return null;
    }

    private static String extractDistributionId(Project project) {
        if (project == null) {
            return null;
        }

        return extractDistributionId(project.getBasePath());
    }

    private static String extractDistributionId(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        String normalized = path.replace('\\', '/');
        String prefix;
        if (normalized.startsWith("//wsl.localhost/")) {
            prefix = "//wsl.localhost/";
        } else if (normalized.startsWith("//wsl$/")) {
            prefix = "//wsl$/";
        } else {
            return null;
        }

        String remainder = normalized.substring(prefix.length());
        int slash = remainder.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        return remainder.substring(0, slash);
    }

    private static Project findBestProject() {
        Project project = ProjectUtil.getActiveProject();
        if (project != null) {
            return project;
        }

        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        return openProjects.length == 0 ? null : openProjects[0];
    }

    private static boolean isUnixAbsolute(String path) {
        return path != null && path.startsWith("/") && !path.startsWith("//");
    }

    private static boolean isPositiveInt(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWindowsHost() {
        return java.io.File.separatorChar == '\\';
    }
}
