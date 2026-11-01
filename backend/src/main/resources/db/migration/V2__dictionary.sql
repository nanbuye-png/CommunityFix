INSERT INTO sys_role(id,role_name,role_code) VALUES (1,'居民','RESIDENT'),(2,'维修人员','WORKER'),(3,'物业管理员','ADMIN'),(4,'系统管理员','SUPER_ADMIN');
INSERT INTO sys_permission(id,permission_code,name) VALUES
 (1,'repair:create','提交报修'),(2,'repair:view','查询工单'),(3,'repair:assign','审核和派单'),(4,'repair:update','处理维修'),
 (5,'system:user','用户管理'),(6,'system:role','角色和权限管理'),(7,'system:community','小区授权管理'),(8,'statistics:view','小区统计');
INSERT INTO sys_role_permission(role_id,permission_id) VALUES (1,1),(1,2),(2,2),(2,4),(3,2),(3,3),(3,8),(4,5),(4,6),(4,7);
INSERT INTO repair_category(id,code,name,sort_order) VALUES (1,'PLUMBING','管道维修',1),(2,'ELECTRIC','水电维修',2),(3,'APPLIANCE','家电维修',3),(4,'OTHER','其他维修',4);
INSERT INTO sla_rule(id,priority,response_minutes,completion_minutes) VALUES (1,'NORMAL',480,1440),(2,'HIGH',120,480),(3,'URGENT',30,240);
