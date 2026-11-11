ALTER TABLE support_knowledge ADD COLUMN aliases VARCHAR(2000) NOT NULL DEFAULT '', ADD COLUMN normalized_question VARCHAR(500) NOT NULL DEFAULT '';
UPDATE support_knowledge SET normalized_question=REGEXP_REPLACE(LOWER(question),'[[:space:]？?！!。]+','');
CREATE INDEX idx_knowledge_question ON support_knowledge(normalized_question(190),status);
CREATE TABLE support_knowledge_alias (
 knowledge_id BIGINT NOT NULL, normalized_alias VARCHAR(500) NOT NULL,
 FOREIGN KEY(knowledge_id) REFERENCES support_knowledge(id) ON DELETE CASCADE,
 INDEX idx_alias_text(normalized_alias(190))
);
UPDATE support_knowledge SET aliases='如何申请维修\n怎样报修\n我要怎么报修' WHERE community_id IS NULL AND question='怎么提交报修？';
UPDATE support_knowledge SET aliases='如何绑定房屋\n房屋怎么绑定' WHERE community_id IS NULL AND question='怎么绑定房屋？';
UPDATE support_knowledge SET aliases='如何转人工\n我要找人工客服' WHERE community_id IS NULL AND question='怎么转人工客服？';
INSERT INTO support_knowledge_alias SELECT id,'如何申请维修' FROM support_knowledge WHERE community_id IS NULL AND question='怎么提交报修？';
INSERT INTO support_knowledge_alias SELECT id,'怎样报修' FROM support_knowledge WHERE community_id IS NULL AND question='怎么提交报修？';
INSERT INTO support_knowledge_alias SELECT id,'我要怎么报修' FROM support_knowledge WHERE community_id IS NULL AND question='怎么提交报修？';
INSERT INTO support_knowledge_alias SELECT id,'如何绑定房屋' FROM support_knowledge WHERE community_id IS NULL AND question='怎么绑定房屋？';
INSERT INTO support_knowledge_alias SELECT id,'房屋怎么绑定' FROM support_knowledge WHERE community_id IS NULL AND question='怎么绑定房屋？';
INSERT INTO support_knowledge_alias SELECT id,'如何转人工' FROM support_knowledge WHERE community_id IS NULL AND question='怎么转人工客服？';
INSERT INTO support_knowledge_alias SELECT id,'我要找人工客服' FROM support_knowledge WHERE community_id IS NULL AND question='怎么转人工客服？';
