/*
 * The MIT License
 *
 * Copyright (c) 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package jenkins.scm.impl.mock;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.TaskListener;
import hudson.scm.SCM;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadCategory;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMProbe;
import jenkins.scm.api.SCMProbeStat;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceDescriptor;
import jenkins.scm.api.SCMSourceEvent;
import jenkins.scm.api.metadata.ContributorMetadataAction;
import jenkins.scm.api.metadata.ObjectMetadataAction;
import jenkins.scm.impl.ChangeRequestSCMHeadCategory;
import jenkins.scm.impl.TagSCMHeadCategory;
import jenkins.scm.impl.UncategorizedSCMHeadCategory;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class MockSCMSource extends SCMSource {
    private final String controllerId;
    private final String repository;
    private final boolean includeBranches;
    private final boolean includeTags;
    private final boolean includeChangeRequests;
    private transient MockSCMController controller;

    @DataBoundConstructor
    public MockSCMSource(@CheckForNull String id, String controllerId, String repository, boolean includeBranches,
                         boolean includeTags, boolean includeChangeRequests) {
        super(id);
        this.controllerId = controllerId;
        this.repository = repository;
        this.includeBranches = includeBranches;
        this.includeTags = includeTags;
        this.includeChangeRequests = includeChangeRequests;
    }

    public MockSCMSource(String id, MockSCMController controller, String repository, boolean includeBranches,
                         boolean includeTags, boolean includeChangeRequests) {
        super(id);
        this.controllerId = controller.getId();
        this.controller = controller;
        this.repository = repository;
        this.includeBranches = includeBranches;
        this.includeTags = includeTags;
        this.includeChangeRequests = includeChangeRequests;
    }

    public String getControllerId() {
        return controllerId;
    }

    private MockSCMController controller() {
        if (controller == null) {
            controller = MockSCMController.lookup(controllerId);
        }
        return controller;
    }

    public String getRepository() {
        return repository;
    }

    public boolean isIncludeBranches() {
        return includeBranches;
    }

    public boolean isIncludeTags() {
        return includeTags;
    }

    public boolean isIncludeChangeRequests() {
        return includeChangeRequests;
    }

    @Override
    protected void retrieve(@CheckForNull SCMSourceCriteria criteria, @NonNull SCMHeadObserver observer,
                            @CheckForNull SCMHeadEvent<?> event, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        controller().applyLatency();
        controller().checkFaults(repository, null, null, false);
        Set<SCMHead> includes = observer.getIncludes();
        if (includeBranches) {
            for (final String branch : controller().listBranches(repository)) {
                checkInterrupt();
                String revision = controller().getRevision(repository, branch);
                MockSCMHead head = new MockSCMHead(branch);
                if (includes != null && !includes.contains(head)) {
                    continue;
                }
                controller().applyLatency();
                controller().checkFaults(repository, head.getName(), null, false);
                if (criteria == null || criteria.isHead(new MockSCMProbe(head, revision), listener)) {
                    controller().applyLatency();
                    controller().checkFaults(repository, head.getName(), revision, false);
                    observer.observe(head, new MockSCMRevision(head, revision));
                }
            }
        }
        if (includeTags) {
            for (final String tag : controller().listTags(repository)) {
                checkInterrupt();
                String revision = controller().getRevision(repository, tag);
                MockSCMHead head = new MockTagSCMHead(tag, controller().getTagTimestamp(repository, tag));
                if (includes != null && !includes.contains(head)) {
                    continue;
                }
                controller().applyLatency();
                controller().checkFaults(repository, head.getName(), null, false);
                if (criteria == null || criteria.isHead(new MockSCMProbe(head, revision), listener)) {
                    controller().applyLatency();
                    controller().checkFaults(repository, head.getName(), revision, false);
                    observer.observe(head, new MockSCMRevision(head, revision));
                }
            }
        }
        if (includeChangeRequests) {
            for (final Integer number : controller().listChangeRequests(repository)) {
                checkInterrupt();
                Set<MockRepositoryFlags> repoFlags = controller().getFlags(repository);
                String revision = controller().getRevision(repository, "change-request/" + number);
                String target = controller().getTarget(repository, number);
                String targetRevision = controller().getRevision(repository, target);
                Set<MockChangeRequestFlags> crFlags = controller.getFlags(repository, number);
                for (boolean merge : new boolean[]{true, false}) {
                    MockChangeRequestSCMHead head;
                    if (repoFlags.contains(MockRepositoryFlags.MERGEABLE)
                            && repoFlags.contains(MockRepositoryFlags.FORKABLE)) {
                        head = new MockDistributedMergeableChangeRequestSCMHead(number, target, merge,
                                crFlags.contains(MockChangeRequestFlags.FORK));
                    } else if (repoFlags.contains(MockRepositoryFlags.MERGEABLE)) {
                        head = new MockMergeableChangeRequestSCMHead(number, target, merge);
                    } else if (crFlags.contains(MockChangeRequestFlags.FORK) && merge) {
                        head = new MockDistributedChangeRequestSCMHead(number, target,
                                crFlags.contains(MockChangeRequestFlags.FORK));
                    } else if (merge) {
                        head = new MockChangeRequestSCMHead(number, target);
                    } else {
                        // we don't want two CRs
                        continue;
                    }
                    if (includes != null && !includes.contains(head)) {
                        continue;
                    }
                    controller().applyLatency();
                    controller().checkFaults(repository, head.getName(), null, false);
                    if (criteria == null || criteria.isHead(new MockSCMProbe(head, revision), listener)) {
                        controller().applyLatency();
                        controller().checkFaults(repository, head.getName(), revision, false);
                        observer.observe(head, new MockChangeRequestSCMRevision(head,
                                new MockSCMRevision(head.getTarget(), targetRevision), revision));
                    }
                }
            }
        }
    }

    @NonNull
    @Override
    public SCM build(@NonNull SCMHead head, @CheckForNull SCMRevision revision) {
        if (revision instanceof MockSCMRevision || revision instanceof MockChangeRequestSCMRevision) {
            return new MockSCM(this, head, revision);
        }
        return new MockSCM(this, head, null);
    }


    @NonNull
    @Override
    protected List<Action> retrieveActions(@CheckForNull SCMSourceEvent event, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        controller().applyLatency();
        controller().checkFaults(repository, null, null, true);
        List<Action> result = new ArrayList<Action>();
        result.add(new MockSCMLink("source"));
        String description = controller().getDescription(repository);
        String displayName = controller().getDisplayName(repository);
        String url = controller().getUrl(repository);
        String iconClassName = controller().getRepoIconClassName();
        if (description != null || displayName != null || url != null) {
            result.add(new ObjectMetadataAction(displayName, description, url));
        }
        if (iconClassName != null) {
            result.add(new MockAvatarMetadataAction(iconClassName));
        }
        return result;
    }

    @NonNull
    @Override
    protected List<Action> retrieveActions(@NonNull SCMRevision revision,
                                           @CheckForNull SCMHeadEvent event,
                                           @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        controller().applyLatency();
        String hash ;
        if (revision instanceof MockSCMRevision) {
            hash = ((MockSCMRevision) revision).getHash();
        } else if (revision instanceof MockChangeRequestSCMRevision) {
            hash = ((MockChangeRequestSCMRevision) revision).getHash();
        } else {
            throw new IOException("Unexpected revision");
        }
        controller().checkFaults(repository, revision.getHead().getName(), hash, true);
        return Collections.<Action>singletonList(new MockSCMLink("revision"));
    }

    @NonNull
    @Override
    protected List<Action> retrieveActions(@NonNull SCMHead head,
                                           @CheckForNull SCMHeadEvent event,
                                           @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        controller().applyLatency();
        controller().checkFaults(repository, head.getName(), null, true);
        List<Action> result = new ArrayList<Action>();
        if (head instanceof MockChangeRequestSCMHead) {
            result.add(new ContributorMetadataAction(
                    "bob",
                    "Bob Smith",
                    "bob@example.com"
            ));
            result.add(new ObjectMetadataAction(
                    String.format("Change request #%d", ((MockChangeRequestSCMHead) head).getNumber()),
                    null,
                    "http://changes.example.com/" + ((MockChangeRequestSCMHead) head).getId()
            ));
        }
        result.add(new MockSCMLink("branch"));
        return result;
    }

    @Override
    protected boolean isCategoryEnabled(@NonNull SCMHeadCategory category) {
        if (category instanceof ChangeRequestSCMHeadCategory) {
            return includeChangeRequests;
        }
        if (category instanceof TagSCMHeadCategory) {
            return includeTags;
        }
        return true;
    }

    @Extension
    public static class DescriptorImpl extends SCMSourceDescriptor {
        @Nonnull
        @Override
        public String getDisplayName() {
            return "Mock SCM";
        }

        public ListBoxModel doFillControllerIdItems() {
            ListBoxModel result = new ListBoxModel();
            for (MockSCMController c : MockSCMController.all()) {
                result.add(c.getId());
            }
            return result;
        }

        public ListBoxModel doFillRepositoryItems(@QueryParameter String controllerId) throws IOException {
            ListBoxModel result = new ListBoxModel();
            MockSCMController c = MockSCMController.lookup(controllerId);
            if (c != null) {
                for (String r : c.listRepositories()) {
                    result.add(r);
                }
            }
            return result;
        }

        @NonNull
        @Override
        protected SCMHeadCategory[] createCategories() {
            return new SCMHeadCategory[]{
                    UncategorizedSCMHeadCategory.DEFAULT,
                    ChangeRequestSCMHeadCategory.DEFAULT,
                    TagSCMHeadCategory.DEFAULT
            };
        }
    }

    private class MockSCMProbe extends SCMProbe {
        private final String revision;
        private final SCMHead head;

        public MockSCMProbe(SCMHead head, String revision) {
            this.revision = revision;
            this.head = head;
        }

        @NonNull
        @Override
        public SCMProbeStat stat(@NonNull String path) throws IOException {
            return SCMProbeStat.fromType(controller().stat(repository, revision, path));
        }

        @Override
        public void close() throws IOException {
        }

        @Override
        public String name() {
            return head.getName();
        }

        @Override
        public long lastModified() {
            return controller().lastModified(repository, revision);
        }
    }
}
