CREATE TABLE repair_reference_price (
 community_id BIGINT NOT NULL, category_id BIGINT NOT NULL,
 labor_fen BIGINT NOT NULL, material_fen BIGINT NOT NULL,
 basis VARCHAR(500) NOT NULL, updated_by BIGINT NOT NULL,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(community_id,category_id),
 FOREIGN KEY(community_id) REFERENCES community(id) ON DELETE CASCADE,
 FOREIGN KEY(category_id) REFERENCES repair_category(id),
 CHECK(labor_fen>=0 AND material_fen>=0 AND labor_fen+material_fen BETWEEN 1 AND 10000000)
);

INSERT INTO support_knowledge(title,keywords,question,answer,audience,status,updated_by) VALUES
 ('提交报修','报修 提交','怎么提交报修？','点击客服下方的“提交报修”，选择已经审核通过的房屋，按页面提示填写故障描述并提交。提交后可在工单详情查看处理进度。','RESIDENT','PUBLISHED',0),
 ('绑定房屋','房屋 绑定','怎么绑定房屋？','点击“房屋绑定”，选择小区和房屋后提交申请，等待物业审核。审核通过后可以使用该房屋提交报修。','RESIDENT','PUBLISHED',0),
 ('查看进度','进度 工单','在哪里查看报修进度？','打开工单列表并进入对应工单详情，可以查看当前状态和处理记录。订单进度消息仅在维修人员接单及居民验收完成时提醒。','RESIDENT','PUBLISHED',0),
 ('费用确认','费用 报价','维修费用怎么确认？','自有设施的维修报价在工单详情的费用区域查看，包含人工和材料等明细。请核对后确认或拒绝；追加费用仍需重新确认。具体金额以该工单报价为准。','RESIDENT','PUBLISHED',0),
 ('收费范围','收费 公共设施','公共设施维修需要我付款吗？','平台将公共设施工单设为居民免付，自有设施按已确认报价计费。请查看工单标注的设施归属，有误时联系物业核实。','RESIDENT','PUBLISHED',0),
 ('维修验收','验收 完工','维修完成后怎么验收？','维修人员提交结果后，打开工单详情检查维修结果，确认完成后提交验收；仍有问题时可按页面提示申请返修。','RESIDENT','PUBLISHED',0),
 ('售后处理','售后 再次故障','验收后又坏了怎么办？','打开已完成的工单，在售后区域描述问题并提交申请，可查看物业回复和处理记录。是否收费需按实际处理方案确认。','RESIDENT','PUBLISHED',0),
 ('人工客服','转人工 客服','怎么转人工客服？','点击会话中的“转人工”按钮进入排队，接待人员接入后可继续在当前会话沟通。当前是否有人接待以会话状态为准。','ALL','PUBLISHED',0),
 ('取消工单','取消 工单','怎么取消报修？','打开工单详情，若当前状态支持取消，页面会显示取消入口；填写原因后提交。没有取消入口时请联系人工客服协调。','RESIDENT','PUBLISHED',0),
 ('上门时间','预约 上门','师傅什么时候上门？','请查看工单详情中的预约信息和处理记录。平台无法根据通用问答确定实际到达时间；需要协调时请联系人工客服。','RESIDENT','PUBLISHED',0),
 ('支付说明','支付 账单','怎么支付维修费？','验收完成后在工单费用区域查看账单，点击“去支付”按页面提示操作。当前仅支持模拟支付，不产生真实扣款；支付超时后可以重新发起。','RESIDENT','PUBLISHED',0),
 ('维修人员操作','接单 维修记录','维修人员怎么处理工单？','在分配给自己的工单详情中接单，按状态进入维修、记录过程并提交维修结果。自费工单需居民先确认报价，实际可操作项以页面为准。','WORKER','PUBLISHED',0);
