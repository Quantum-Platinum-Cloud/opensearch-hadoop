/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */
 
/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.opensearch.storm.security;

import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.opensearch.hadoop.cfg.Settings;
import org.opensearch.hadoop.security.OpenSearchToken;
import org.opensearch.hadoop.security.UserProvider;
import org.opensearch.hadoop.util.ClusterInfo;
import org.opensearch.hadoop.util.ClusterName;

/**
 * Storm bolts and spouts don't actually ever run anything on the driver side of a topology submission.
 * There's a chance for them to do so, but it would require all configurations to be passed into the
 * object when it is constructed, which means that the code misses out on defaults, cluster level, and
 * topology level settings.
 *
 * Since nothing runs in the client, if using Kerberos + Tokens the integration cannot get the cluster
 * name via Kerberos in order to look up a token by that cluster name later once the Kerberos session
 * has ended.
 *
 * Thus, this is a sort of hack that picks a cluster name from the list of tokens on a user in order
 * to find that token by name later.
 */
public class OpenSearchClusterInfoSelector {

    private static final Log LOG = LogFactory.getLog(OpenSearchClusterInfoSelector.class);

    public static void populate(Settings settings) {
        Iterable<OpenSearchToken> opensearchTokens = UserProvider.create(settings).getUser().getAllOpenSearchTokens();
        // There should only be one token here at any given time since the auto creds
        // only get one token from one cluster for one user per topology, and that token
        // is keyed by the cluster name, making it so that there shouldn't be any other
        // tokens for that cluster on the subject
        if (LOG.isDebugEnabled()) {
            LOG.debug("Found list of tokens on worker: " + opensearchTokens);
        }
        Iterator<OpenSearchToken> iterator = opensearchTokens.iterator();
        if (iterator.hasNext()) {
            OpenSearchToken opensearchToken = iterator.next();
            if (LOG.isDebugEnabled()) {
                LOG.debug("Using token: " + opensearchToken);
            }
            ClusterInfo clusterInfo = new ClusterInfo(new ClusterName(opensearchToken.getClusterName(), null), opensearchToken.getMajorVersion());
            if (LOG.isDebugEnabled()) {
                LOG.debug("Using clusterInfo : " + clusterInfo);
            }
            settings.setInternalClusterInfo(clusterInfo);
        } else {
            LOG.debug("Could not locate any tokens");
        }
    }
}