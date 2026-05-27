-- Performance indexes for the capability authorization hot path and related queries

-- capabilityGrant: composite index for the evaluator's per-invocation grant lookup
ALTER TABLE `capabilityGrant`
    ADD INDEX `idx_cg_grantee_capability` (`granteeId`, `capability`);

-- permission: covering indexes for direct-permission and role-permission checks
ALTER TABLE `permission`
    ADD INDEX `idx_perm_user_resource_action` (`userId`, `resource`, `action`),
    ADD INDEX `idx_perm_role_resource_action` (`roleId`, `resource`, `action`);

-- approvalRequest: index for findApprovedRequest (Once / Session approval modes)
ALTER TABLE `approvalRequest`
    ADD INDEX `idx_ar_user_cap_status` (`requestorUserId`, `capability`, `status`);

-- schedulerJob: composite index covering the status filter + scheduledAt sort in getPendingJobs
ALTER TABLE `schedulerJob`
    ADD INDEX `idx_sj_status_scheduled` (`status`, `scheduledAt`);

-- eventLog: composite index for replaySession queries
ALTER TABLE `eventLog`
    ADD INDEX `idx_event_log_session` (`sessionId`, `occurredAt`);
