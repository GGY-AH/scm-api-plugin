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
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceEvent;

public class MockSCMSourceEvent extends SCMSourceEvent<String> {

    private final MockSCMController controller;

    private final String repository;

    @Deprecated
    public MockSCMSourceEvent(@NonNull Type type, MockSCMController controller,
                              String repository) {
        super(type, repository);
        this.controller = controller;
        this.repository = repository;
    }

    public MockSCMSourceEvent(@CheckForNull String origin, @NonNull Type type, MockSCMController controller,
                              String repository) {
        super(type, repository, origin);
        this.controller = controller;
        this.repository = repository;
    }

    @Override
    public boolean isMatch(@NonNull SCMNavigator navigator) {
        return navigator instanceof MockSCMNavigator
                && ((MockSCMNavigator) navigator).getControllerId().equals(controller.getId());
    }

    @Override
    public boolean isMatch(@NonNull SCMSource source) {
        return source instanceof MockSCMSource
                && ((MockSCMSource) source).getControllerId().equals(controller.getId())
                && repository.equals(((MockSCMSource) source).getRepository());
    }

    @NonNull
    @Override
    public String getSourceName() {
        return repository;
    }
}
