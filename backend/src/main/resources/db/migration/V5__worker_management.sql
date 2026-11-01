ALTER TABLE worker_profile ADD COLUMN owner_community_id BIGINT NULL,
 ADD CONSTRAINT fk_worker_owner_community FOREIGN KEY (owner_community_id) REFERENCES community(id);

UPDATE worker_profile w JOIN (
 SELECT worker_id, MIN(community_id) AS community_id
 FROM worker_service_community GROUP BY worker_id HAVING COUNT(*) = 1
) s ON s.worker_id=w.id SET w.owner_community_id=s.community_id;
