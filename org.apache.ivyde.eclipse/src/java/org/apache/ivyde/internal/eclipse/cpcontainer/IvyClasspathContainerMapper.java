/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.ivyde.internal.eclipse.cpcontainer;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ivy.Ivy;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ArtifactDownloadReport;
import org.apache.ivy.core.resolve.DownloadOptions;
import org.apache.ivy.osgi.core.BundleInfo;
import org.apache.ivy.osgi.core.ExportPackage;
import org.apache.ivy.osgi.core.ManifestParser;
import org.apache.ivyde.eclipse.cp.ClasspathSetup;
import org.apache.ivyde.eclipse.cp.IvyClasspathContainerConfiguration;
import org.apache.ivyde.eclipse.cp.MappingSetup;
import org.apache.ivyde.internal.eclipse.IvyDEMessage;
import org.apache.ivyde.internal.eclipse.IvyPlugin;
import org.apache.ivyde.internal.eclipse.resolve.ResolveResult;
import org.apache.ivyde.internal.eclipse.workspaceresolver.WorkspaceResolver;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IAccessRule;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

/**
 * This class is mapping the resolved artifacts between them. After a resolve process, this class
 * will build the classpath based on the retrieved artifacts and will search for sources and
 * javadocs and make them attached.
 */
public class IvyClasspathContainerMapper {

    private static final String IVYDE_NS = "http://ant.apache.org/ivy/ivyde/ns/";

    private static final String IVYDE_NS_PREFIX = "ivyde:";

    private final IProgressMonitor monitor;

    private final Ivy ivy;

    private final IJavaProject javaProject;

    private final Collection<ArtifactDownloadReport> all;

    private final Map<ModuleRevisionId, Artifact[]> artifactsByDependency;

    private final Map<ArtifactDownloadReport, Set<String>> retrievedArtifacts;

    private ClasspathSetup classpathSetup;

    private MappingSetup mapping;

    private boolean osgiAvailable;

    private boolean osgiClasspathAvailable;

    private IvyAttachementManager attachementManager = IvyPlugin.getDefault()
            .getIvyAttachementManager();

    public IvyClasspathContainerMapper(IProgressMonitor monitor, Ivy ivy,
            IvyClasspathContainerConfiguration conf, ResolveResult resolveResult) {
        this.monitor = monitor;
        this.ivy = ivy;
        this.javaProject = conf.getJavaProject();
        this.classpathSetup = conf.getInheritedClasspathSetup();
        this.mapping = conf.getInheritedMappingSetup();
        this.all = resolveResult.getArtifactReports();
        this.artifactsByDependency = resolveResult.getArtifactsByDependency();
        this.retrievedArtifacts = resolveResult.getRetrievedArtifacts();
        this.osgiAvailable = IvyPlugin.getDefault().isOsgiAvailable();
        this.osgiClasspathAvailable = IvyPlugin.getDefault().isOsgiClasspathAvailable();
    }

    public IClasspathEntry[] map() {
        IClasspathEntry[] classpathEntries;
        Collection<IClasspathEntry> paths = new LinkedHashSet<IClasspathEntry>();

        IvyDEMessage.verbose("Building classpath from " + all.size() + " resolved artifact(s)");

        for (ArtifactDownloadReport artifact : all) {
            if (artifact.getType().equals(WorkspaceResolver.ECLIPSE_PROJECT_TYPE)) {
                IvyDEMessage.verbose("Found an workspace dependency on project "
                        + artifact.getName());
                // This is a java project in the workspace, add project path
                // but only add it if it is not a self dependency
                if (javaProject == null
                        || !artifact.getName().equals(javaProject.getPath().toString())) {
                    IAccessRule[] rules = getAccessRules(javaProject);
                    paths.add(JavaCore.newProjectEntry(new Path(artifact.getName()), rules, true,
                        null, true));
                } else {
                    IvyDEMessage.verbose("Skipping self dependency on project "
                            + artifact.getName());
                }
            } else if (artifact.getLocalFile() != null && accept(artifact.getArtifact())) {
                IvyDEMessage.verbose("Adding " + artifact.getName() + " to the classpath");

                // handle unzipped jar with 'Bundle-Classpath'
                if (osgiClasspathAvailable && artifact.getLocalFile().isDirectory()
                        && classpathSetup.isReadOSGiMetadata()) {
                    File manifestFile = new File(artifact.getLocalFile(), "META-INF/MANIFEST.MF");
                    if (!manifestFile.exists()) {
                        // no manifest : back to simple classpath
                        paths.add(buildEntry(artifact));
                    } else {
                        try {
                            BundleInfo bundleInfo = ManifestParser.parseManifest(manifestFile);
                            if (bundleInfo.getClasspath() == null) {
                                // no inner classpath : a simple entry
                                paths.add(buildEntry(artifact));
                            } else {
                                IAccessRule[] rules = getAccessRules(bundleInfo);
                                for (String innerPath : bundleInfo.getClasspath()) {
                                    IClasspathEntry buildEntry = buildEntry(artifact, rules,
                                        manifestFile, innerPath);
                                    if (buildEntry != null) {
                                        paths.add(buildEntry);
                                    }
                                }
                            }
                        } catch (IOException e) {
                            IvyDEMessage.error(
                                "Unreadable MANIFEST.MF for artifact " + artifact.getName() + ": "
                                        + manifestFile.getAbsolutePath() + " (" + e.getMessage()
                                        + ")", e);
                        } catch (ParseException e) {
                            IvyDEMessage.error(
                                "Malformed MANIFEST.MF for artifact " + artifact.getName() + ": "
                                        + manifestFile.getAbsolutePath() + " (" + e.getMessage()
                                        + ")", e);
                        }
                    }
                } else {
                    // simple entry
                    paths.add(buildEntry(artifact));
                }
            }

        }
        classpathEntries = (IClasspathEntry[]) paths.toArray(new IClasspathEntry[paths.size()]);

        return classpathEntries;
    }

    private IClasspathEntry buildEntry(ArtifactDownloadReport artifact, IAccessRule[] rules,
            File manifestFile, String innerPath) {
        IPath classpathArtifact = getArtifactPath(artifact, "/" + innerPath);
        if (!classpathArtifact.toFile().exists()) {
            // an non existing inner jar is 'just' a broken MANIFEST.MF, which happens sometimes
            // with Eclipse bundles
            IvyDEMessage.warn("The MANIFEST of " + artifact + " (" + manifestFile
                    + ") is referencing a non exitant jar " + classpathArtifact + ". Ignoring it");
            return null;
        }
        return doBuildEntry(artifact, classpathArtifact, rules);
    }

    private IClasspathEntry buildEntry(ArtifactDownloadReport artifact) {
        IPath classpathArtifact = getArtifactPath(artifact, "");
        return doBuildEntry(artifact, classpathArtifact, null);
    }

    private IClasspathEntry doBuildEntry(ArtifactDownloadReport artifact, IPath classpathArtifact,
            IAccessRule[] rules) {
        IPath sourcesArtifact = getArtifactPath(artifact, sourceArtifactMatcher,
            mapping.isMapIfOnlyOneSource(), "");
        IPath javadocArtifact = getArtifactPath(artifact, javadocArtifactMatcher,
            mapping.isMapIfOnlyOneJavadoc(), "");
        IPath sources = attachementManager.getSourceAttachment(classpathArtifact, sourcesArtifact);
        IPath sourcesRoot = attachementManager.getSourceAttachmentRoot(classpathArtifact,
            sourcesArtifact);
        IClasspathAttribute[] att = getExtraAttribute(classpathArtifact, javadocArtifact);

        if (sources != null) {
            IvyDEMessage.debug("Attaching sources " + sources + " to " + classpathArtifact);
        }
        if (javadocArtifact != null) {
            IvyDEMessage.debug("Attaching javadoc " + javadocArtifact + " to " + classpathArtifact);
        }
        if (rules != null) {
            IvyDEMessage.debug("Setting OSGi access rules on  " + classpathArtifact);
        }
        return JavaCore.newLibraryEntry(classpathArtifact, sources, sourcesRoot, rules, att, false);
    }

    private IAccessRule[] getAccessRules(IJavaProject javaProject) {
        if (!osgiAvailable || !classpathSetup.isReadOSGiMetadata()) {
            return null;
        }
        // TODO
        // Nicolas: AFAIU, the access rules seems to have to be set on the imported project itself
        // rather than filtering here, afterwards
        return null;
    }

    private IAccessRule[] getAccessRules(BundleInfo bundleInfo) {
        if (bundleInfo == null || !classpathSetup.isReadOSGiMetadata()) {
            return null;
        }
        IAccessRule[] rules = new IAccessRule[bundleInfo.getExports().size() + 1];
        int i = 0;
        for (ExportPackage exportPackage : bundleInfo.getExports()) {
            rules[i++] = JavaCore.newAccessRule(
                new Path(exportPackage.getName().replace('.', IPath.SEPARATOR) + "/*"),
                IAccessRule.K_ACCESSIBLE);
        }
        rules[i++] = JavaCore.newAccessRule(new Path("**/*"), IAccessRule.K_NON_ACCESSIBLE
                | IAccessRule.IGNORE_IF_BETTER);
        return rules;
    }

    private Path getArtifactPath(ArtifactDownloadReport artifact, String innerPath) {
        if (retrievedArtifacts != null) {
            Set<String> pathSet = retrievedArtifacts.get(artifact);
            if (pathSet != null && !pathSet.isEmpty()) {
                return new Path((String) pathSet.iterator().next() + innerPath);
            }
        }
        return new Path(artifact.getLocalFile().getAbsolutePath() + innerPath);
    }

    interface ArtifactMatcher {
        boolean matchName(Artifact artifact, String artifactName);

        boolean match(Artifact a);

        String getName();
    }

    private Path getArtifactPath(ArtifactDownloadReport adr, ArtifactMatcher matcher,
            boolean mapIfOnlyOne, String innerPath) {
        Artifact artifact = adr.getArtifact();
        monitor.subTask("searching " + matcher.getName() + " for " + artifact);
        for (ArtifactDownloadReport otherAdr : all) {
            Artifact a = otherAdr.getArtifact();
            if (otherAdr.getLocalFile() != null && matcher.matchName(artifact, a.getName())
                    && a.getModuleRevisionId().equals(artifact.getModuleRevisionId())
                    && matcher.match(a)) {
                return getArtifactPath(otherAdr, innerPath);
            }
        }
        // we haven't found source artifact in resolved artifacts,
        // let's look in the module declaring the artifact
        ModuleRevisionId mrid = artifact.getId().getModuleRevisionId();
        Artifact[] artifacts = (Artifact[]) artifactsByDependency.get(mrid);
        if (artifacts != null) {
            Artifact foundArtifact = null;
            int nbFound = 0;
            for (int i = 0; i < artifacts.length; i++) {
                Artifact metaArtifact = artifacts[i];
                if (matcher.match(metaArtifact)) {
                    if (matcher.matchName(artifact, metaArtifact.getName())) {
                        // we've found a matching artifact, let's provision it
                        ArtifactDownloadReport metaAdr = ivy.getResolveEngine().download(
                            metaArtifact, new DownloadOptions());
                        if (metaAdr.getLocalFile() != null && metaAdr.getLocalFile().exists()) {
                            return getArtifactPath(metaAdr, innerPath);
                        }
                    }
                    // keep a reference to the artifact so we could fall back
                    // to map-if-only-one
                    nbFound++;
                    foundArtifact = metaArtifact;
                }
            }
            if (mapIfOnlyOne) {
                // we haven't found artifact in the module declaring the artifact and having
                // a matching name.
                if (nbFound == 1) {
                    // If there is only 1 found artifact, it is the winner ;-)
                    ArtifactDownloadReport metaAdr = ivy.getResolveEngine().download(foundArtifact,
                        new DownloadOptions());
                    if (metaAdr.getLocalFile() != null && metaAdr.getLocalFile().exists()) {
                        return new Path(metaAdr.getLocalFile().getAbsolutePath());
                    }
                }
            }
        }

        return null;
    }

    private ArtifactMatcher sourceArtifactMatcher = new ArtifactMatcher() {
        public boolean matchName(Artifact artifact, String source) {
            return isArtifactName(artifact, source, mapping.getSourceSuffixes(), "source");
        }

        public boolean match(Artifact a) {
            return mapping.getSourceTypes().contains(a.getType());
        }

        public String getName() {
            return "sources";
        }
    };

    private ArtifactMatcher javadocArtifactMatcher = new ArtifactMatcher() {
        public boolean matchName(Artifact artifact, String javadoc) {
            return isArtifactName(artifact, javadoc, mapping.getJavadocSuffixes(), "javadoc");
        }

        public boolean match(Artifact a) {
            return mapping.getJavadocTypes().contains(a.getType());
        }

        public String getName() {
            return "javadoc";
        }
    };

    private boolean isArtifactName(Artifact artifact, String name, Collection<String> suffixes,
            String type) {
        String artifactNameToMatch = (String) artifact.getExtraAttribute(IVYDE_NS_PREFIX + type);
        if (artifactNameToMatch != null) {
            // some name is specified, it overrides suffix matching
            return name.equals(artifactNameToMatch);
        }
        String jar = artifact.getName();
        if (name.equals(jar)) {
            return true;
        }
        for (String suffix : suffixes) {
            if (name.equals(jar + suffix)) {
                return true;
            }
        }
        return false;
    }

    private IClasspathAttribute[] getExtraAttribute(IPath classpathArtifact, IPath javadocArtifact) {
        List<IClasspathAttribute> result = new ArrayList<IClasspathAttribute>();
        URL url = attachementManager.getDocAttachment(classpathArtifact);

        if (url == null) {
            IPath path = javadocArtifact;
            if (path != null) {
                String u;
                try {
                    u = "jar:" + path.toFile().toURI().toURL().toExternalForm() + "!/";
                    try {
                        url = new URL(u);
                    } catch (MalformedURLException e) {
                        // this should not happen
                        IvyPlugin.logError("The jar URL for the javadoc is not formed correctly "
                                + u, e);
                    }
                } catch (MalformedURLException e) {
                    // this should not happen
                    IvyPlugin.logError("The path has not a correct URL: " + path, e);
                }
            }
        }

        if (url != null) {
            result.add(JavaCore.newClasspathAttribute(
                IClasspathAttribute.JAVADOC_LOCATION_ATTRIBUTE_NAME, url.toExternalForm()));
        }
        return result.toArray(new IClasspathAttribute[result.size()]);
    }

    /**
     * Check if the artifact is an artifact which can be added to the classpath container
     * 
     * @param artifact
     *            the artifact to check
     * @return <code>true</code> if the artifact can be added
     */
    public boolean accept(Artifact artifact) {
        boolean accepted = classpathSetup.getAcceptedTypes().contains(artifact.getType());
        if (!accepted && classpathSetup.getAcceptedTypes().size() == 1
                && classpathSetup.getAcceptedTypes().get(0).equals("*")) {
            accepted = true;
        }
        return accepted && !mapping.getSourceTypes().contains(artifact.getType())
                && !mapping.getJavadocTypes().contains(artifact.getType());
    }

}
