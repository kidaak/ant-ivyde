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

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivyde.eclipse.IvyDEException;
import org.apache.ivyde.eclipse.cp.IvyClasspathContainerConfiguration;
import org.apache.ivyde.internal.eclipse.IvyPlugin;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.ui.packageview.ClassPathContainer;

public final class IvyClasspathUtil {

    private IvyClasspathUtil() {
        // utility class
    }

    /**
     * Work around the non adaptability of ClassPathContainer
     * 
     * @param cpc
     *            the container to transform into an IvyClasspathContainer
     * @return the IvyClasspathContainer is such, null, if not
     */
    public static IvyClasspathContainerImpl jdt2IvyCPC(ClassPathContainer cpc) {
        IClasspathEntry entry = cpc.getClasspathEntry();
        try {
            IClasspathContainer icp = JavaCore.getClasspathContainer(entry.getPath(),
                cpc.getJavaProject());
            if (icp instanceof IvyClasspathContainerImpl) {
                return (IvyClasspathContainerImpl) icp;
            }
        } catch (JavaModelException e) {
            // unless there are issues with the JDT, this should never happen
            IvyPlugin.log(e);
        }
        return null;
    }

    public static List split(String str) {
        String[] terms = str.split(",");
        List ret = new ArrayList();
        for (int i = 0; i < terms.length; i++) {
            String t = terms[i].trim();
            if (t.length() > 0) {
                ret.add(t);
            }
        }
        return ret;
    }

    public static String concat(Collection/* <String> */list) {
        if (list == null) {
            return "";
        }
        StringBuffer b = new StringBuffer();
        Iterator it = list.iterator();
        while (it.hasNext()) {
            b.append(it.next());
            if (it.hasNext()) {
                b.append(",");
            }
        }
        return b.toString();
    }

    /**
     * Just a verbatim copy of the internal Eclipse function:
     * org.eclipse.jdt.internal.corext.javadoc
     * .JavaDocLocations#getLibraryJavadocLocation(IClasspathEntry)
     * 
     * @param entry
     * @return
     */
    public static URL getLibraryJavadocLocation(IClasspathEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Entry must not be null"); //$NON-NLS-1$
        }

        int kind = entry.getEntryKind();
        if (kind != IClasspathEntry.CPE_LIBRARY && kind != IClasspathEntry.CPE_VARIABLE) {
            throw new IllegalArgumentException(
                    "Entry must be of kind CPE_LIBRARY or " + "CPE_VARIABLE"); //$NON-NLS-1$
        }

        IClasspathAttribute[] extraAttributes = entry.getExtraAttributes();
        for (int i = 0; i < extraAttributes.length; i++) {
            IClasspathAttribute attrib = extraAttributes[i];
            if (IClasspathAttribute.JAVADOC_LOCATION_ATTRIBUTE_NAME.equals(attrib.getName())) {
                try {
                    return new URL(attrib.getValue());
                } catch (MalformedURLException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Rewrites the module descriptor back to project's ivy file.
     * 
     * @throws IOException
     * @throws ParseException
     * @throws IvyDEException
     */
    public static void toIvyFile(ModuleDescriptor descriptor, IvyClasspathContainerImpl container)
            throws ParseException, IOException, IvyDEException {
        IvyClasspathContainerConfiguration conf = container.getConf();
        // TODO the ivy file might not be in the workspace or may be an absolute path
        // in a such case the Eclipse API will state the file a read only
        IFile ivyFile = conf.getJavaProject().getProject().getFile(conf.getIvyXmlPath());
        IStatus writable = ivyFile.getWorkspace().validateEdit(new IFile[] {ivyFile},
            IWorkspace.VALIDATE_PROMPT);
        if (writable.isOK()) {
            descriptor.toIvyFile(container.getState().getIvyFile());
        }
    }

    /**
     * Build the resolve id used when reading and writing resolve reports.
     * 
     * @param conf
     *            The IvyClasspathContainerConfiguration indicating if extended resolve id is being
     *            used.
     * @param md
     *            The ModuleDescriptor to be resolved.
     * @return The resolve id.
     */
    public static String buildResolveId(boolean useExtendedResolveId, ModuleDescriptor md) {
        StringBuffer sb = new StringBuffer(ResolveOptions.getDefaultResolveId(md));
        if (useExtendedResolveId) {
            ModuleRevisionId mrid = md.getModuleRevisionId();
            String sts = md.getStatus();
            String bch = mrid.getBranch();
            String rev = mrid.getRevision();
            sb.append("-");
            if (sts != null) {
                sb.append(sts);
            }
            sb.append("-");
            if (bch != null) {
                sb.append(bch);
            }
            sb.append("-");
            if (rev != null) {
                sb.append(rev);
            }
        }
        return sb.toString();
    }

}
