/*
 * Copyright 2003-2013 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.mapreduce.utilities;

import java.util.LinkedHashSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.marklogic.mapreduce.DocumentURI;

public abstract class AssignmentPolicy {
    public static final Log LOG = LogFactory.getLog(AssignmentPolicy.class);

    public enum Kind {
        LEGACY, BUCKET, RANGE, STATISTICAL
    };

    protected Kind policy;
    /**
     * updatable forests
     */
    protected LinkedHashSet<String> uForests;

    public Kind getPolicyKind() {
        return policy;
    }

    public abstract String getPlacementForestId(DocumentURI uri);

    public abstract int getPlacementForestIndex(DocumentURI uri);
}
