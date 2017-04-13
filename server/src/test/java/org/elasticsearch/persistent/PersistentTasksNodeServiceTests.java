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

package org.elasticsearch.persistent;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.node.tasks.cancel.CancelTasksResponse;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.tasks.TaskManager;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData.Assignment;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData.PersistentTask;
import org.elasticsearch.persistent.TestPersistentTasksPlugin.TestParams;
import org.elasticsearch.persistent.TestPersistentTasksPlugin.TestPersistentTasksExecutor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PersistentTasksNodeServiceTests extends ESTestCase {

    private ClusterService createClusterService() {
        ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        return new ClusterService(Settings.builder().put("cluster.name", "PersistentActionExecutorTests").build(),
                clusterSettings, null, () -> new DiscoveryNode(UUIDs.randomBase64UUID(), buildNewFakeTransportAddress(),
                Version.CURRENT));
    }

    private DiscoveryNodes createTestNodes(int nonLocalNodesCount, Settings settings) {
        DiscoveryNodes.Builder nodes = DiscoveryNodes.builder();
        nodes.add(DiscoveryNode.createLocal(settings, buildNewFakeTransportAddress(), "this_node"));
        for (int i = 0; i < nonLocalNodesCount; i++) {
            nodes.add(new DiscoveryNode("other_node_" + i, buildNewFakeTransportAddress(), Version.CURRENT));
        }
        nodes.localNodeId("this_node");
        return nodes.build();
    }

    public void testStartTask() throws Exception {
        ClusterService clusterService = createClusterService();
        PersistentTasksService persistentTasksService = mock(PersistentTasksService.class);
        @SuppressWarnings("unchecked") PersistentTasksExecutor<TestParams> action = mock(PersistentTasksExecutor.class);
        when(action.getExecutor()).thenReturn(ThreadPool.Names.SAME);
        when(action.getTaskName()).thenReturn(TestPersistentTasksExecutor.NAME);
        int nonLocalNodesCount = randomInt(10);
        // need to account for 5 original tasks on each node and their relocations
        for (int i = 0; i < (nonLocalNodesCount + 1) * 10; i++) {
            TaskId parentId = new TaskId("cluster", i);
            when(action.createTask(anyLong(), anyString(), anyString(), eq(parentId), any())).thenReturn(
                    new TestPersistentTasksPlugin.TestTask(i, "persistent", "test", "", parentId));
        }
        PersistentTasksExecutorRegistry registry = new PersistentTasksExecutorRegistry(Settings.EMPTY, Collections.singletonList(action));

        MockExecutor executor = new MockExecutor();
        PersistentTasksNodeService coordinator = new PersistentTasksNodeService(Settings.EMPTY, persistentTasksService,
                registry, new TaskManager(Settings.EMPTY), executor);

        ClusterState state = ClusterState.builder(clusterService.state()).nodes(createTestNodes(nonLocalNodesCount, Settings.EMPTY))
                .build();

        PersistentTasksCustomMetaData.Builder tasks = PersistentTasksCustomMetaData.builder();
        boolean added = false;
        if (nonLocalNodesCount > 0) {
            for (int i = 0; i < randomInt(5); i++) {
                tasks.addTask(UUIDs.base64UUID(), TestPersistentTasksExecutor.NAME, new TestParams("other_" + i),
                        new Assignment("other_node_" + randomInt(nonLocalNodesCount), "test assignment on other node"));
                if (added == false && randomBoolean()) {
                    added = true;
                    tasks.addTask(UUIDs.base64UUID(), TestPersistentTasksExecutor.NAME, new TestParams("this_param"),
                            new Assignment("this_node", "test assignment on this node"));
                }
            }
        }

        if (added == false) {
            logger.info("No local node action was added");

        }
        MetaData.Builder metaData = MetaData.builder(state.metaData());
        metaData.putCustom(PersistentTasksCustomMetaData.TYPE, tasks.build());
        ClusterState newClusterState = ClusterState.builder(state).metaData(metaData).build();

        coordinator.clusterChanged(new ClusterChangedEvent("test", newClusterState, state));
        if (added) {
            // Action for this node was added, let's make sure it was invoked
            assertThat(executor.executions.size(), equalTo(1));

            // Add task on some other node
            state = newClusterState;
            newClusterState = addTask(state, TestPersistentTasksExecutor.NAME, null, "some_other_node");
            coordinator.clusterChanged(new ClusterChangedEvent("test", newClusterState, state));

            // Make sure action wasn't called again
            assertThat(executor.executions.size(), equalTo(1));

            // Start another task on this node
            state = newClusterState;
            newClusterState = addTask(state, TestPersistentTasksExecutor.NAME, new TestParams("this_param"), "this_node");
            coordinator.clusterChanged(new ClusterChangedEvent("test", newClusterState, state));

            // Make sure action was called this time
            assertThat(executor.size(), equalTo(2));

            // Finish both tasks
            executor.get(0).task.markAsFailed(new RuntimeException());
            executor.get(1).task.markAsCompleted();
            String failedTaskId = executor.get(0).task.getPersistentTaskId();
            String finishedTaskId = executor.get(1).task.getPersistentTaskId();
            executor.clear();

            // Add task on some other node
            state = newClusterState;
            newClusterState = addTask(state, TestPersistentTasksExecutor.NAME, null, "some_other_node");
            coordinator.clusterChanged(new ClusterChangedEvent("test", newClusterState, state));

            // Make sure action wasn't called again
            assertThat(executor.size(), equalTo(0));

            // Simulate reallocation of the failed task on the same node
            state = newClusterState;
            newClusterState = reallocateTask(state, failedTaskId, "this_node");
            coordinator.clusterChanged(new ClusterChangedEvent("test", newClusterState, state));

            // Simulate removal of the finished task
            state = newClusterState;
            newClusterState = removeTask(state, finishedTaskId);
            coordinator.clusterChanged(new ClusterChangedEvent("test", newClusterState, state));

            // Make sure action was only allocated on this node once
            assertThat(executor.size(), equalTo(1));
        }

    }

    public void testTaskCancellation() {
        ClusterService clusterService = createClusterService();
        AtomicLong capturedTaskId = new AtomicLong();
        AtomicReference<ActionListener<CancelTasksResponse>> capturedListener = new AtomicReference<>();
        PersistentTasksService persistentTasksService = new PersistentTasksService(Settings.EMPTY, null, null, null) {
            @Override
            public void sendTaskManagerCancellation(long taskId, ActionListener<CancelTasksResponse> listener) {
                capturedTaskId.set(taskId);
                capturedListener.set(listener);
            }

            @Override
            public void sendCompletionNotification(String taskId, Exception failure, ActionListener<PersistentTask<?>> listener) {
                fail("Shouldn't be called during Cluster State cancellation");
            }
        };
        @SuppressWarnings("unchecked") PersistentTasksExecutor<TestParams> action = mock(PersistentTasksExecutor.class);
        when(action.getExecutor()).thenReturn(ThreadPool.Names.SAME);
        when(action.getTaskName()).thenReturn("test");
        when(action.createTask(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new TestPersistentTasksPlugin.TestTask(1, "persistent", "test", "", new TaskId("cluster", 1)));
        PersistentTasksExecutorRegistry registry = new PersistentTasksExecutorRegistry(Settings.EMPTY, Collections.singletonList(action));

        int nonLocalNodesCount = randomInt(10);
        MockExecutor executor = new MockExecutor();
        TaskManager taskManager = new TaskManager(Settings.EMPTY);
        PersistentTasksNodeService coordinator = new PersistentTasksNodeService(Settings.EMPTY, persistentTasksService,
                registry, taskManager, executor);

        ClusterState state = ClusterState.builder(clusterService.state()).nodes(createTestNodes(nonLocalNodesCount, Settings.EMPTY))
                .build();

        ClusterState newClusterState = state;
        // Allocate first task
        state = newClusterState;
        newClusterState = addTask(state, "test", null, "this_node");
        coordinator.clusterChanged(new ClusterChangedEvent("test", newClusterState, state));

        // Check the the task is know to the task manager
        assertThat(taskManager.getTasks().size(), equalTo(1));
        AllocatedPersistentTask runningTask = (AllocatedPersistentTask)taskManager.getTasks().values().iterator().next();
        String persistentId = runningTask.getPersistentTaskId();
        long localId = runningTask.getId();
        // Make sure it returns correct status
        Task.Status status = runningTask.getStatus();
        assertThat(status.toString(), equalTo("{\"state\":\"STARTED\"}"));

        state = newClusterState;
        // Relocate the task to some other node or remove it completely
        if (randomBoolean()) {
            newClusterState = reallocateTask(state, persistentId, "some_other_node");
        } else {
            newClusterState = removeTask(state, persistentId);
        }
        coordinator.clusterChanged(new ClusterChangedEvent("test", newClusterState, state));

        // Make sure it returns correct status
        assertThat(taskManager.getTasks().size(), equalTo(1));
        assertThat(taskManager.getTasks().values().iterator().next().getStatus().toString(), equalTo("{\"state\":\"PENDING_CANCEL\"}"));


        // That should trigger cancellation request
        assertThat(capturedTaskId.get(), equalTo(localId));
        // Notify successful cancellation
        capturedListener.get().onResponse(new CancelTasksResponse());

        // finish or fail task
        if (randomBoolean()) {
            executor.get(0).task.markAsCompleted();
        } else {
            executor.get(0).task.markAsFailed(new IOException("test"));
        }

        // Check the the task is now removed from task manager
        assertThat(taskManager.getTasks().values(), empty());

    }

    private <Params extends PersistentTaskParams> ClusterState addTask(ClusterState state, String action, Params params,
                                                                       String node) {
        PersistentTasksCustomMetaData.Builder builder =
                PersistentTasksCustomMetaData.builder(state.getMetaData().custom(PersistentTasksCustomMetaData.TYPE));
        return ClusterState.builder(state).metaData(MetaData.builder(state.metaData()).putCustom(PersistentTasksCustomMetaData.TYPE,
                builder.addTask(UUIDs.base64UUID(), action, params, new Assignment(node, "test assignment")).build())).build();
    }

    private ClusterState reallocateTask(ClusterState state, String taskId, String node) {
        PersistentTasksCustomMetaData.Builder builder =
                PersistentTasksCustomMetaData.builder(state.getMetaData().custom(PersistentTasksCustomMetaData.TYPE));
        assertTrue(builder.hasTask(taskId));
        return ClusterState.builder(state).metaData(MetaData.builder(state.metaData()).putCustom(PersistentTasksCustomMetaData.TYPE,
                builder.reassignTask(taskId, new Assignment(node, "test assignment")).build())).build();
    }

    private ClusterState removeTask(ClusterState state, String taskId) {
        PersistentTasksCustomMetaData.Builder builder =
                PersistentTasksCustomMetaData.builder(state.getMetaData().custom(PersistentTasksCustomMetaData.TYPE));
        assertTrue(builder.hasTask(taskId));
        return ClusterState.builder(state).metaData(MetaData.builder(state.metaData()).putCustom(PersistentTasksCustomMetaData.TYPE,
                builder.removeTask(taskId).build())).build();
    }

    private class Execution {
        private final PersistentTaskParams params;
        private final AllocatedPersistentTask task;
        private final PersistentTasksExecutor<?> holder;

        Execution(PersistentTaskParams params, AllocatedPersistentTask task, PersistentTasksExecutor<?> holder) {
            this.params = params;
            this.task = task;
            this.holder = holder;
        }
    }

    private class MockExecutor extends NodePersistentTasksExecutor {
        private List<Execution> executions = new ArrayList<>();

        MockExecutor() {
            super(null);
        }

        @Override
        public <Params extends PersistentTaskParams> void executeTask(Params params, AllocatedPersistentTask task,
                                                                      PersistentTasksExecutor<Params> executor) {
            executions.add(new Execution(params, task, executor));
        }

        public Execution get(int i) {
            return executions.get(i);
        }

        public int size() {
            return executions.size();
        }

        public void clear() {
            executions.clear();
        }
    }

}
