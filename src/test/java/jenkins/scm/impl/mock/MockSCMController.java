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
import hudson.FilePath;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.scm.api.SCMFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.jvnet.hudson.test.recipes.LocalData;

public class MockSCMController implements Closeable {

    private static Map<String, MockSCMController> instances = new WeakHashMap<String, MockSCMController>();

    private final String id;

    private Map<String, Repository> repositories = new TreeMap<String, Repository>();
    private List<MockFailure> faults = new ArrayList<MockFailure>();
    @NonNull
    private MockLatency latency = MockLatency.none();
    private String displayName;
    private String description;
    private String url;
    private String repoIconClassName;
    private String orgIconClassName;

    private MockSCMController() {
        this(UUID.randomUUID().toString());
    }

    public MockSCMController(String id) {
        this.id = id;
    }

    public static MockSCMController create() {
        MockSCMController c = new MockSCMController();
        synchronized (instances) {
            instances.put(c.id, c);
        }
        return c;
    }

    public MockSCMController withLatency(@NonNull MockLatency latency) {
        this.latency = latency;
        return this;
    }

    /**
     * (Re)creates a {@link MockSCMController} for use when you are running a data migration test.
     * It will be the callers responsibility to restore the state of the {@link MockSCMController} accordingly.
     * Only use this if you are using {@link LocalData} which contains references to specific {@link MockSCMController}
     * ids and you need to re-wire up the link.
     *
     * @param id the ID of the controller used when seeding the original data.
     * @return the {@link MockSCMController}.
     * @deprecated (not actually deprecated) a warning that you should only be using this from a test annotated with
     * {@link LocalData}
     */
    @Deprecated // only intended for use from data migration tests where you have a pre-provisioned home
    public static MockSCMController recreate(String id) {
        MockSCMController c = new MockSCMController(id);
        synchronized (instances) {
            if (instances.containsKey(id)) {
                return instances.get(id);
            }
            instances.put(id, c);
        }
        return c;

    }

    public static List<MockSCMController> all() {
        synchronized (instances) {
            return new ArrayList<MockSCMController>(instances.values());
        }
    }

    public static MockSCMController lookup(String id) {
        synchronized (instances) {
            return instances.get(id);
        }
    }

    public String getId() {
        return id;
    }

    @Override
    public void close() {
        synchronized (instances) {
            instances.remove(id);
        }
        repositories.clear();
    }

    public String getRepoIconClassName() {
        return repoIconClassName;
    }

    public void setRepoIconClassName(String repoIconClassName) {
        this.repoIconClassName = repoIconClassName;
    }

    public String getOrgIconClassName() {
        return orgIconClassName;
    }

    public void setOrgIconClassName(String orgIconClassName) {
        this.orgIconClassName = orgIconClassName;
    }

    public String getDescription() throws IOException {
        return description;
    }

    public void setDescription(String description) throws IOException {
        this.description = description;
    }

    public String getDisplayName() throws IOException {
        return displayName;
    }

    public void setDisplayName(String displayName) throws IOException {
        this.displayName = displayName;
    }

    public String getUrl() throws IOException {
        return url;
    }

    public void setUrl(String url) throws IOException {
        this.url = url;
    }

    public synchronized void addFault(MockFailure fault) {
        this.faults.add(fault);
    }

    public synchronized void clearFaults() {
        this.faults.clear();
    }

    public void applyLatency() throws InterruptedException {
        MockLatency latency;
        synchronized (this) {
            latency = this.latency;
        }
        latency.apply();
    }

    public void checkFaults(@CheckForNull String repository, @CheckForNull String branch,
                                         @CheckForNull String revision, boolean actions)
            throws IOException, InterruptedException {
        List<MockFailure> faults;
        synchronized (this) {
            faults = new ArrayList<MockFailure>(this.faults);
        }
        for (MockFailure fault: faults) {
            fault.check(repository, branch, revision, actions);
        }
    }

    public synchronized void createRepository(String name) throws IOException {
        repositories.put(name, new Repository());
        createBranch(name, "master");
    }

    public synchronized void deleteRepository(String name) throws IOException {
        repositories.remove(name);
    }

    public synchronized List<String> listRepositories() throws IOException {
        return new ArrayList<String>(repositories.keySet());
    }

    public String getDescription(String repository) throws IOException {
        return resolve(repository).description;
    }

    public void setDescription(String repository, String description) throws IOException {
        resolve(repository).description = description;
    }

    public String getDisplayName(String repository) throws IOException {
        return resolve(repository).displayName;
    }

    public void setDisplayName(String repository, String displayName) throws IOException {
        resolve(repository).displayName = displayName;
    }

    public String getUrl(String repository) throws IOException {
        return resolve(repository).url;
    }

    public void setUrl(String repository, String url) throws IOException {
        resolve(repository).url = url;
    }

    public synchronized void createBranch(String repository, String branch) throws IOException {
        State state = new State();
        Repository repo = resolve(repository);
        repo.revisions.put(state.getHash(), state);
        repo.heads.put(branch, state.getHash());
    }

    public synchronized void cloneBranch(String repository, String srcBranch, String dstBranch) throws IOException {
        Repository repo = resolve(repository);
        repo.heads.put(dstBranch, repo.heads.get(srcBranch));
    }

    public synchronized void deleteBranch(String repository, String branch) throws IOException {
        resolve(repository).heads.remove(branch);
    }

    public synchronized long createTag(String repository, String branch, String tag) throws IOException {
        Repository repo = resolve(repository);
        long timestamp = System.currentTimeMillis();
        repo.tags.put(tag, resolve(repository, branch).getHash());
        repo.tagDates.put(tag, timestamp);
        return timestamp;
    }

    public synchronized void createTag(String repository, String branch, String tag, long when) throws IOException {
        Repository repo = resolve(repository);
        repo.tags.put(tag, resolve(repository, branch).getHash());
        repo.tagDates.put(tag, when);
    }

    public synchronized void deleteTag(String repository, String tag) throws IOException {
        resolve(repository).tags.remove(tag);
        resolve(repository).tagDates.remove(tag);
    }

    public synchronized Integer openChangeRequest(String repository, String branch) throws IOException {
        Repository repo = resolve(repository);
        String hash = resolve(repository, branch).getHash();
        Integer crNum = ++repo.lastChangeRequest;
        repo.changes.put(crNum, hash);
        repo.changeBaselines.put(crNum, branch);
        return crNum;
    }

    public synchronized void closeChangeRequest(String repository, Integer crNum) throws IOException {
        resolve(repository).changes.remove(crNum);
        resolve(repository).changeBaselines.remove(crNum);
    }

    public synchronized String getTarget(String repository, Integer crNum) throws IOException {
        return resolve(repository).changeBaselines.get(crNum);
    }

    public synchronized List<String> listBranches(String repository) throws IOException {
        return new ArrayList<String>(resolve(repository).heads.keySet());
    }

    public synchronized List<String> listTags(String repository) throws IOException {
        return new ArrayList<String>(resolve(repository).tags.keySet());
    }

    public synchronized List<Integer> listChangeRequests(String repository) throws IOException {
        return new ArrayList<Integer>(resolve(repository).changes.keySet());
    }

    public synchronized String getRevision(String repository, String branch) throws IOException {
        return resolve(repository, branch).getHash();
    }

    public synchronized void addFile(String repository, String branchOrCR, String message, String path, byte[] content)
            throws IOException {
        Repository repo = resolve(repository);
        String branchName;
        Integer crNum;
        String hash;
        // check branch first
        hash = repo.heads.get(branchOrCR);
        if (hash == null) {
            branchName = null;
            Matcher m = Pattern.compile("change-request/(\\d+)").matcher(branchOrCR);
            if (m.matches()) {
                crNum = Integer.valueOf(m.group(1));
                hash = repo.changes.get(crNum);
                if (hash == null) {
                    throw new IOException("Unknown change request: " + crNum + " in repository " + repository);
                }
            } else {
                throw new IOException("Unknown branch: " + branchOrCR + " in repository " + repository);
            }
        } else {
            branchName = branchOrCR;
            crNum = null;
        }
        State base = repo.revisions.get(hash);
        State state = new State(base, message, Collections.singletonMap(path, content), Collections.<String>emptySet());
        repo.revisions.put(state.getHash(), state);
        if (branchName != null) {
            repo.heads.put(branchName, state.getHash());
        }
        if (crNum != null) {
            repo.changes.put(crNum, state.getHash());
        }
    }

    public synchronized void rmFile(String repository, String branchOrCR, String message, String path)
            throws IOException {
        Repository repo = resolve(repository);
        String branchName;
        Integer crNum;
        String hash;
        // check branch first
        hash = repo.heads.get(branchOrCR);
        if (hash == null) {
            branchName = null;
            Matcher m = Pattern.compile("change-request/(\\d+)").matcher(branchOrCR);
            if (m.matches()) {
                crNum = Integer.valueOf(m.group(1));
                hash = repo.changes.get(crNum);
                if (hash == null) {
                    throw new IOException("Unknown change request: " + crNum + " in repository " + repository);
                }
            } else {
                throw new IOException("Unknown branch: " + branchOrCR + " in repository " + repository);
            }
        } else {
            branchName = branchOrCR;
            crNum = null;
        }
        State base = repo.revisions.get(hash);
        State state =
                new State(base, message, Collections.<String, byte[]>emptyMap(), Collections.<String>singleton(path));
        repo.revisions.put(state.getHash(), state);
        if (branchName != null) {
            repo.heads.put(branchName, state.getHash());
        }
        if (crNum != null) {
            repo.changes.put(crNum, state.getHash());
        }
    }


    public synchronized String checkout(File workspace, String repository, String identifier) throws IOException {
        State state = resolve(repository, identifier);

        for (Map.Entry<String, byte[]> entry : state.files.entrySet()) {
            FileUtils.writeByteArrayToFile(new File(workspace, entry.getKey()), entry.getValue());
        }
        return state.getHash();
    }

    public synchronized String checkout(FilePath workspace, String repository, String identifier)
            throws IOException, InterruptedException {
        State state = resolve(repository, identifier);

        for (Map.Entry<String, byte[]> entry : state.files.entrySet()) {
            workspace.child(entry.getKey()).copyFrom(new ByteArrayInputStream(entry.getValue()));
        }
        return state.getHash();
    }

    private synchronized State resolve(String repository, String identifier) throws IOException {
        Repository repo = resolve(repository);
        // check hash first
        String hash = repo.revisions.containsKey(identifier) ? identifier : null;
        if (hash != null) {
            return repo.revisions.get(hash);
        }
        // now check for a named branch
        hash = repo.heads.get(identifier);
        if (hash != null) {
            return repo.revisions.get(hash);
        }
        // now check for a named tag
        hash = repo.tags.get(identifier);
        if (hash != null) {
            return repo.revisions.get(hash);
        }
        // now check for a change request
        Matcher m = Pattern.compile("change-request/(\\d+)").matcher(identifier);
        if (m.matches()) {
            Integer crNum = Integer.valueOf(m.group(1));
            hash = repo.changes.get(crNum);
            if (hash != null) {
                return repo.revisions.get(hash);
            }
            throw new IOException("Unknown change request: " + crNum + " in repository " + repository);
        }
        throw new IOException("Unknown branch/tag/revision: " + identifier + " in repository " + repository);
    }

    private Repository resolve(String repository) throws IOException {
        Repository repo = repositories.get(repository);
        if (repo == null) {
            throw new IOException("Unknown repository: " + repository);
        }
        return repo;
    }

    public synchronized List<LogEntry> log(String repository, String identifier) throws IOException {
        State state = resolve(repository, identifier);
        List<LogEntry> result = new ArrayList<LogEntry>();
        while (state != null) {
            result.add(new LogEntry(state.getHash(), state.timestamp, state.message));
            state = state.parent;
        }
        return result;
    }

    public synchronized SCMFile.Type stat(String repository, String identifier, String path) throws IOException {
        State state = resolve(repository, identifier);
        if (state == null) {
            return SCMFile.Type.NONEXISTENT;
        }
        if (state.files.containsKey(path)) {
            return SCMFile.Type.REGULAR_FILE;
        }
        for (String p : state.files.keySet()) {
            if (p.startsWith(path + "/")) {
                return SCMFile.Type.DIRECTORY;
            }
        }
        return SCMFile.Type.NONEXISTENT;
    }

    public synchronized long lastModified(String repository, String identifier) {
        try {
            State state = resolve(repository, identifier);
            if (state == null) {
                return 0L;
            }
            return state.timestamp;
        } catch (IOException e) {
            return 0L;
        }
    }

    public synchronized long getTagTimestamp(String repository, String tag) throws IOException {
        Repository repo = repositories.get(repository);
        if (repo == null) {
            throw new IOException("Unknown repository: " + repository);
        }
        Long date = repo.tagDates.get(tag);
        if (tag == null) {
            throw new IOException("Unknown tag: " + tag + " in repository " + repository);
        }
        return date;
    }

    private static class Repository {
        private Map<String, State> revisions = new TreeMap<String, State>();
        private Map<String, String> heads = new TreeMap<String, String>();
        private Map<String, String> tags = new TreeMap<String, String>();
        private Map<String, Long> tagDates = new TreeMap<String, Long>();
        private Map<Integer, String> changes = new TreeMap<Integer, String>();
        private Map<Integer, String> changeBaselines = new TreeMap<Integer, String>();
        private int lastChangeRequest;
        private String description;
        private String displayName;
        private String url;
    }

    private static class State {
        private final State parent;
        private final String message;
        private final long timestamp;
        private final Map<String, byte[]> files;
        private transient String hash;

        public State() {
            this.parent = null;
            this.message = null;
            this.timestamp = System.currentTimeMillis();
            this.files = new TreeMap<String, byte[]>();
        }

        public State(State parent, String message, Map<String, byte[]> added, Set<String> removed) {
            this.parent = parent;
            this.message = message;
            this.timestamp = System.currentTimeMillis();
            Map<String, byte[]> files = parent != null
                    ? new TreeMap<String, byte[]>(parent.files)
                    : new TreeMap<String, byte[]>();
            files.keySet().removeAll(removed);
            files.putAll(added);
            this.files = files;
        }

        public String getHash() {
            if (hash == null) {
                try {
                    Charset utf8 = Charset.forName("UTF-8");
                    MessageDigest sha = MessageDigest.getInstance("SHA-1");
                    if (parent != null) {
                        sha.update(new BigInteger(parent.getHash(), 16).toByteArray());
                    }
                    sha.update(StringUtils.defaultString(message).getBytes(utf8));
                    sha.update((byte) (timestamp & 0xff));
                    sha.update((byte) ((timestamp >> 8) & 0xff));
                    sha.update((byte) ((timestamp >> 16) & 0xff));
                    sha.update((byte) ((timestamp >> 24) & 0xff));
                    sha.update((byte) ((timestamp >> 32) & 0xff));
                    sha.update((byte) ((timestamp >> 40) & 0xff));
                    sha.update((byte) ((timestamp >> 48) & 0xff));
                    sha.update((byte) ((timestamp >> 56) & 0xff));
                    for (Map.Entry<String, byte[]> e : files.entrySet()) {
                        sha.update(e.getKey().getBytes(utf8));
                        sha.update(e.getValue());
                    }
                    hash = javax.xml.bind.DatatypeConverter.printHexBinary(sha.digest()).toLowerCase();
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException("SHA-1 message digest mandated by JLS");
                }
            }
            return hash;
        }
    }

    public static final class LogEntry {
        private final String hash;
        private final long timestamp;
        private final String message;

        private LogEntry(String hash, long timestamp, String message) {
            this.hash = hash;
            this.timestamp = timestamp;
            this.message = message;
        }

        public String getHash() {
            return hash;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return String.format("Commit %s%nDate: %tc%n%s%n", hash, timestamp, message);
        }
    }
}
