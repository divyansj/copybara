/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.testing;

import com.google.copybara.RepoException;
import com.google.copybara.TransformResult;
import java.nio.file.Path;

/**
 * Utility methods related to {@link TransformResult}.
 */
public class TransformResults {
  private TransformResults() {}

  /** Creates an instance with reasonable defaults for testing. */
  public static TransformResult of(Path path, DummyRevision originRef) throws RepoException {
    return new TransformResult(path, originRef, originRef.getAuthor(), "test summary\n",
                               originRef)
        .withIdentity(originRef.asString() /*groupIdentity=*/);
  }
}
