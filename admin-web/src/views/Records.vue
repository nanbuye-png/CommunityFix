<script setup lang="ts">
import { ref,computed,onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Plus,RefreshCw } from 'lucide-vue-next'
import { ElMessage,ElMessageBox } from 'element-plus'
import { get,post,patch,remove,date,statusNames } from '../api'
import { useSession } from '../stores/session'
const route=useRoute(),session=useSession(),section=String(route.params.section),rows=ref<any[]>([]),busy=ref(false),total=ref(0),page=ref(1),open=ref(false),form=ref<any>({}),saving=ref(false),communities=ref<any[]>([]),users=ref<any[]>([]),roles=ref<any[]>([]),permissions=ref<any[]>([]),categories=ref<any[]>([]),baseType=ref('communities'),communityId=ref(''),buildingId=ref(''),unitId=ref(''),buildings=ref<any[]>([]),units=ref<any[]>([])
const config:Record<string,any>={
bindings:{title:'房屋绑定审核',description:'先确认房屋归属，再开启居民报修服务。',endpoint:'/room-bindings',columns:[['nickname','居民'],['communityName','小区'],['buildingNo','楼栋'],['unitNo','单元'],['roomNo','房号'],['status','审核状态'],['reviewReason','审核说明']]},
complaints:{title:'投诉处理',description:'倾听居民反馈，保留每一次处理记录。',endpoint:'/complaints',columns:[['orderNo','工单编号'],['title','报修事项'],['content','投诉内容'],['status','处理状态'],['resolution','处理说明']]},
notices:{title:'社区公告',description:'把居民需要知道的事情，及时告知。',endpoint:'/notices',columns:[['title','公告标题'],['status','发布状态'],['publishedAt','发布时间'],['createdAt','创建时间']]},
notifications:{title:'消息通知',description:'查看与你相关的服务进展。',endpoint:'/notifications',columns:[['title','消息标题'],['content','消息内容'],['readStatus','阅读状态'],['createdAt','收到时间']]},
users:{title:'用户管理',description:'修改账号资料；居民搬家时保留旧报修地址与审核记录。',endpoint:'/system/users',columns:[['username','账号'],['nickname','姓名 / 昵称'],['phone','联系电话'],['addresses','当前房屋'],['roles','角色'],['status','账号状态'],['createdAt','创建时间']]},
roles:{title:'角色与权限',description:'设置每个角色可以执行的操作。',endpoint:'/system/roles',columns:[['roleName','角色'],['roleCode','编码'],['permissions','操作权限']]},
grants:{title:'小区授权',description:'将物业管理范围明确到小区。',endpoint:'/system/admin-communities',columns:[['nickname','物业管理员'],['name','授权小区'],['userId','用户编号']]},
logs:{title:'操作记录',description:'查看账号与授权变更的审计记录。',endpoint:'/system/logs',columns:[['action','操作'],['operatorId','操作者编号'],['targetType','对象'],['targetId','对象编号'],['createdAt','时间'],['requestId','请求标识']]},
workers:{title:'维修人员',description:'查看服务能力、区域与当前任务负载。',endpoint:'/workers',columns:[['realName','维修人员'],['workStatus','工作状态'],['activeOrders','当前任务'],['maxActiveOrders','接单容量'],['categoryIds','技能分类编号'],['communityIds','服务小区编号']]},
base:{title:'小区与房屋',description:'维护小区、楼栋、单元与房屋的层级关系。',endpoint:'/manage/communities',columns:[]}
}
const info=config[section]
const title=computed(()=>info.title)
const baseColumns:Record<string,any>={communities:[['name','小区'],['address','地址'],['status','状态']],buildings:[['buildingNo','楼栋编号'],['status','状态']],units:[['unitNo','单元编号'],['status','状态']],rooms:[['roomNo','房号'],['floor','楼层'],['status','状态']]}
const columns=computed(()=>section==='base'?baseColumns[baseType.value]:info.columns)
const createAllowed=computed(()=>['notices','users','roles','grants'].includes(section)||(section==='workers'&&session.can('system:user'))||(section==='base'&&(baseType.value!=='communities'||session.can('system:community'))))
const texts:Record<string,string>={PENDING:'待审核',APPROVED:'已通过',REJECTED:'已驳回',REVOKED:'已撤销',OPEN:'待受理',PROCESSING:'处理中',CLOSED:'已关闭',DRAFT:'草稿',PUBLISHED:'已发布',OFFLINE:'已下架',ENABLED:'启用',DISABLED:'停用',AVAILABLE:'可接单',ON_LEAVE:'休假中',RESIDENT:'居民',WORKER:'维修人员',ADMIN:'物业管理员',SUPER_ADMIN:'系统管理员'}
function format(value:any,name:string){if(name.endsWith('At'))return date(value);if(name==='readStatus')return Number(value)===1?'已读':'未读';if(name==='addresses')return value?.length?value.map((a:any)=>`${a.communityName} ${a.buildingNo}栋${a.unitNo}单元${a.roomNo}室`).join('、'):'—';if(Array.isArray(value))return value.map(v=>texts[v]??v).join('、');return texts[value]??value??'—'}
async function load(){busy.value=true;try{if(section==='base'){await loadBase();return}const result=await get(info.endpoint,{page:page.value,size:20});rows.value=Array.isArray(result)?result:result.records;total.value=Array.isArray(result)?result.length:result.total}catch{}finally{busy.value=false}}
async function loadBase(){communities.value=await get('/manage/communities');if(!communities.value.some(c=>c.id===communityId.value))communityId.value=communities.value[0]?.id??'';if(baseType.value==='communities'){rows.value=communities.value;total.value=rows.value.length;return}buildings.value=communityId.value?await get('/manage/buildings',{communityId:communityId.value}):[];if(!buildings.value.some(b=>b.id===buildingId.value))buildingId.value=buildings.value[0]?.id??'';if(baseType.value==='buildings'){rows.value=buildings.value;total.value=rows.value.length;return}units.value=buildingId.value?await get('/manage/units',{buildingId:buildingId.value}):[];if(!units.value.some(u=>u.id===unitId.value))unitId.value=units.value[0]?.id??'';rows.value=baseType.value==='units'?units.value:unitId.value?await get('/manage/rooms',{unitId:unitId.value}):[];total.value=rows.value.length}
async function create(){form.value={roles:['RESIDENT'],permissions:[],categoryIds:[],communityIds:[],maxActiveOrders:5,floor:1};if(['notices','grants','workers'].includes(section))communities.value=await get('/manage/communities');if(['users','roles'].includes(section))roles.value=await get('/system/roles');if(section==='roles')permissions.value=await get('/system/permissions');if(['grants','workers'].includes(section)){users.value=(await get('/system/users',{size:100})).records}if(section==='workers')categories.value=await get('/repair/categories');open.value=true}
async function save(){saving.value=true;try{let endpoint=info.endpoint;let body=form.value;if(section==='base'){endpoint=`/manage/${baseType.value}`;body={...body,communityId:communityId.value,buildingId:buildingId.value,unitId:unitId.value}}await post(endpoint,body);ElMessage.success('已保存');open.value=false;await load()}finally{saving.value=false}}
async function prompt(title:string,callback:(reason:string)=>Promise<unknown>){try{const input=await ElMessageBox.prompt('请填写处理说明',title,{inputValidator:v=>!!v?.trim()||'请填写处理说明',inputType:'textarea',confirmButtonText:'确认',cancelButtonText:'取消'});await callback(input.value);ElMessage.success('已更新');await load()}catch{}}
const bindingBusy=ref(false)
async function binding(row:any,action:'approve'|'reject'|'revoke'){
  if(bindingBusy.value)return
  bindingBusy.value=true
  try{
    let reason=''
    if(action==='approve')await ElMessageBox.confirm(`确认通过 ${row.nickname} 对 ${row.communityName} ${row.buildingNo}栋${row.unitNo}单元${row.roomNo}室的绑定申请？`,'审核房屋绑定',{confirmButtonText:'确认通过',cancelButtonText:'取消'})
    else{
      const input=await ElMessageBox.prompt('请填写原因，居民可在小程序中查看。',action==='reject'?'驳回绑定申请':'撤销房屋绑定',{inputType:'textarea',inputValidator:v=>!!v?.trim()&&v.trim().length<=500||'请填写1至500字的原因',confirmButtonText:'确认',cancelButtonText:'取消'})
      reason=input.value.trim()
    }
    await post(`/room-bindings/${row.id}/${action==='revoke'?'revoke':'review'}`,action==='revoke'?{reason}:{approved:action==='approve',reason})
    ElMessage.success(action==='approve'?'房屋绑定已通过':action==='reject'?'申请已驳回':'房屋绑定已撤销')
    await load()
  }catch{/* 取消无需提示；接口错误由统一拦截器显示。 */}finally{bindingBusy.value=false}
}
const complaintOpen=ref(false),complaintLoading=ref(false),complaintError=ref(false),complaintId=ref(''),complaintDetail=ref<any>(null),complaintResolution=ref(''),complaintSaving=ref(false)
async function showComplaint(row:any){
  const id=String(row.id)
  complaintId.value=id;complaintOpen.value=true;complaintDetail.value=null;complaintResolution.value='';complaintError.value=false;complaintLoading.value=true
  try{const detail=await get(`/complaints/${id}`);if(complaintId.value===id)complaintDetail.value=detail}
  catch{if(complaintId.value===id)complaintError.value=true}
  finally{if(complaintId.value===id)complaintLoading.value=false}
}
function recordClick(row:any){if(section==='complaints')void showComplaint(row)}
async function processComplaint(){
  const complaint=complaintDetail.value?.complaint
  if(!complaint||complaintSaving.value||!['OPEN','PROCESSING'].includes(complaint.status))return
  const resolution=complaintResolution.value.trim()
  if(!resolution||resolution.length>1000){ElMessage.warning('请填写1至1000字的处理说明');return}
  complaintSaving.value=true
  try{
    const status=complaint.status==='OPEN'?'PROCESSING':'CLOSED'
    await post(`/complaints/${complaint.id}/process`,{version:complaint.version,status,resolution})
    ElMessage.success(status==='PROCESSING'?'投诉已受理':'处理结果已提交，投诉已关闭')
    await showComplaint(complaint)
    await load()
  }catch(error:any){if(error.response?.status===409)await showComplaint(complaint)}finally{complaintSaving.value=false}
}
async function toggle(row:any){await patch(section==='users'?`/system/users/${row.id}`:`/manage/${baseType.value}/${row.id}`,{status:row.status==='ENABLED'?'DISABLED':'ENABLED'});await load()}
async function deleteCommunity(row:any){
 if(busy.value)return
 busy.value=true
 try{
  const check=await get(`/manage/communities/${row.id}/deletion-check`)
  if(!check.canCleanup){await ElMessageBox.alert('存在以下业务数据：'+check.businessBlockers.map((b:any)=>b.label+' '+b.count+' 条').join('、')+'。不能连带删除历史业务数据，请使用停用。','无法删除小区',{type:'warning'});return}
  const c=check.configurationCounts
  await ElMessageBox.confirm(`确认永久删除“${check.community.name}”（${check.community.address}，ID：${check.community.id}），同时清理 ${c.buildings} 栋楼、${c.units} 个单元、${c.rooms} 套空房屋和 ${c.grants} 条物业授权？受影响物业需重新登录，其他小区授权保留。此操作无法撤销。`,'删除小区及空配置',{type:'warning',confirmButtonText:'确认删除',cancelButtonText:'取消'})
  await remove(`/manage/communities/${row.id}?cleanupEmptyConfiguration=true&confirmationToken=${encodeURIComponent(check.configurationToken)}`)
  if(communityId.value===row.id){communityId.value='';buildingId.value='';unitId.value='';buildings.value=[];units.value=[]}
  ElMessage.success('小区已删除');await load()
 }catch{}finally{busy.value=false}
}
async function grantRevoke(row:any){try{await ElMessageBox.confirm('撤销后，该管理员将无法访问此小区。','撤销小区授权',{type:'warning'});await remove(`/system/admin-communities/${row.userId}/${row.communityId}`);await load()}catch{}}
async function roleEdit(row:any){permissions.value=await get('/system/permissions');form.value={...row,editing:true};open.value=true}
async function saveEditedRole(){saving.value=true;try{await patch(`/system/roles/${form.value.id}`,form.value);open.value=false;await load()}finally{saving.value=false}}
async function userEdit(row:any){roles.value=await get('/system/roles');form.value={id:row.id,username:row.username,nickname:row.nickname,phone:row.phone??'',roles:[...row.roles],password:'',editing:true};open.value=true}
async function saveEditedUser(){saving.value=true;try{const body:any={nickname:form.value.nickname,phone:form.value.phone};if(form.value.id!==session.user?.id)body.roles=form.value.roles;if(form.value.password)body.password=form.value.password;await patch(`/system/users/${form.value.id}`,body);if(form.value.id===session.user?.id&&session.user)session.user.nickname=form.value.nickname;ElMessage.success('用户资料已更新');open.value=false;await load()}finally{saving.value=false}}
const moveOpen=ref(false),moveUser=ref<any>(null),moveForm=ref<any>({}),moveCommunities=ref<any[]>([]),moveBuildings=ref<any[]>([]),moveUnits=ref<any[]>([]),moveRooms=ref<any[]>([])
async function userMove(row:any){moveUser.value=row;moveForm.value={oldBindingId:row.addresses?.[0]?.bindingId??'',communityId:'',buildingId:'',unitId:'',newRoomId:'',reason:''};moveCommunities.value=(await get('/manage/communities')).filter((c:any)=>c.status==='ENABLED');moveBuildings.value=[];moveUnits.value=[];moveRooms.value=[];moveOpen.value=true}
async function moveCommunity(){moveForm.value.buildingId='';moveForm.value.unitId='';moveForm.value.newRoomId='';moveBuildings.value=moveForm.value.communityId?(await get('/manage/buildings',{communityId:moveForm.value.communityId})).filter((b:any)=>b.status==='ENABLED'):[];moveUnits.value=[];moveRooms.value=[]}
async function moveBuilding(){moveForm.value.unitId='';moveForm.value.newRoomId='';moveUnits.value=moveForm.value.buildingId?(await get('/manage/units',{buildingId:moveForm.value.buildingId})).filter((u:any)=>u.status==='ENABLED'):[];moveRooms.value=[]}
async function moveUnit(){moveForm.value.newRoomId='';moveRooms.value=moveForm.value.unitId?(await get('/manage/rooms',{unitId:moveForm.value.unitId})).filter((r:any)=>r.status==='ENABLED'):[]}
async function saveMove(){if(!moveForm.value.oldBindingId||!moveForm.value.newRoomId||!moveForm.value.reason?.trim()){ElMessage.warning('请选择原房屋、新房屋并填写搬家原因');return}saving.value=true;try{await post(`/system/users/${moveUser.value.id}/move`,{oldBindingId:moveForm.value.oldBindingId,newRoomId:moveForm.value.newRoomId,reason:moveForm.value.reason});ElMessage.success('房屋地址已更新');moveOpen.value=false;await load()}finally{saving.value=false}}
onMounted(load)
</script>
<style scoped>
.complaints-table :deep(.el-table__row){cursor:pointer}
.complaint-detail{min-height:180px}
.complaint-detail .description{overflow-wrap:anywhere}
.complaint-detail .el-timeline{padding-left:14px}
</style>
<template><div class="page-heading"><div><span class="eyebrow">社区服务 · {{title}}</span><h1>{{title}}</h1><p class="muted">{{info.description}}</p></div><div class="action-row"><button class="secondary-button" @click="load"><RefreshCw :size="16"/>刷新</button><el-button v-if="createAllowed" type="primary" @click="create"><Plus :size="16"/>{{section==='grants'?'添加授权':'新建'}}</el-button></div></div>
<section class="panel"><div v-if="section==='base'" class="base-toolbar"><el-radio-group v-model="baseType" @change="load"><el-radio-button value="communities">小区</el-radio-button><el-radio-button value="buildings">楼栋</el-radio-button><el-radio-button value="units">单元</el-radio-button><el-radio-button value="rooms">房屋</el-radio-button></el-radio-group><el-select v-if="baseType!=='communities'" v-model="communityId" placeholder="选择小区" @change="buildingId='';unitId='';load()"><el-option v-for="item in communities" :key="item.id" :label="item.name" :value="item.id"/></el-select><el-select v-if="['units','rooms'].includes(baseType)" v-model="buildingId" placeholder="选择楼栋" @change="unitId='';load()"><el-option v-for="item in buildings" :key="item.id" :label="`${item.buildingNo}栋`" :value="item.id"/></el-select><el-select v-if="baseType==='rooms'" v-model="unitId" placeholder="选择单元" @change="load"><el-option v-for="item in units" :key="item.id" :label="`${item.unitNo}单元`" :value="item.id"/></el-select></div>
<el-table :data="rows" v-loading="busy" :class="{'complaints-table':section==='complaints'}" empty-text="暂无记录" @row-click="recordClick"><el-table-column v-for="[field,label] in columns" :key="field" :label="label" :min-width="['buildingNo','unitNo','roomNo'].includes(field)?80:['content','permissions','addresses'].includes(field)?240:130"><template #default="{row}"><span :class="{'muted':field==='createdAt','state-pill':field==='status'}">{{format(row[field],field)}}</span></template></el-table-column><el-table-column v-if="!['logs','workers'].includes(section)" label="操作" fixed="right" :width="section==='users'?230:190"><template #default="{row}">
<template v-if="section==='bindings'"><el-button v-if="row.status==='PENDING'" :disabled="bindingBusy" link type="primary" @click="binding(row,'approve')">通过</el-button><el-button v-if="row.status==='PENDING'" :disabled="bindingBusy" link @click="binding(row,'reject')">驳回</el-button><el-button v-if="row.status==='APPROVED'" :disabled="bindingBusy" link type="danger" @click="binding(row,'revoke')">撤销绑定</el-button><span v-if="['REJECTED','REVOKED'].includes(row.status)" class="muted">等待居民重新申请</span></template>
<template v-if="section==='complaints'"><el-button link type="primary" @click.stop="showComplaint(row)">查看详情</el-button><el-button v-if="row.status!=='CLOSED'" link type="primary" @click.stop="showComplaint(row)">{{row.status==='OPEN'?'受理':'处理'}}</el-button></template>
<template v-if="section==='notices'"><el-button link type="primary" @click="post(`/notices/${row.id}/${row.status==='PUBLISHED'?'offline':'publish'}`).then(load)">{{row.status==='PUBLISHED'?'下架':'发布'}}</el-button></template>
<template v-if="section==='notifications'"><RouterLink v-if="row.supportSessionId" :to="`/support?id=${row.supportSessionId}`">查看咨询</RouterLink><el-button v-if="!Number(row.readStatus)" link type="primary" @click="post(`/notifications/${row.id}/read`).then(load)">标记已读</el-button></template>
<template v-if="section==='users'"><el-button link type="primary" @click="userEdit(row)">编辑</el-button><el-button v-if="row.roles?.includes('RESIDENT')&&row.addresses?.length" link type="primary" @click="userMove(row)">搬家</el-button></template>
<template v-if="section==='base'&&baseType==='communities'&&session.roles.includes('SUPER_ADMIN')&&session.can('system:community')"><el-button link type="danger" :disabled="busy" @click="deleteCommunity(row)">删除</el-button></template><template v-if="['users','base'].includes(section)"><el-button link :type="row.status==='ENABLED'?'danger':'primary'" :disabled="section==='users'&&row.id===session.user?.id" @click="toggle(row)">{{row.status==='ENABLED'?'停用':'启用'}}</el-button></template>
<el-button v-if="section==='roles'&&row.roleCode!=='SUPER_ADMIN'" link type="primary" @click="roleEdit(row)">配置权限</el-button><el-button v-if="section==='grants'" link type="danger" @click="grantRevoke(row)">撤销授权</el-button>
</template></el-table-column></el-table><div class="pagination"><span>共 {{total}} 条记录</span><el-pagination v-if="!['roles','grants','workers','base'].includes(section)" v-model:current-page="page" :total="total" :page-size="20" layout="prev,pager,next" @current-change="load"/></div></section>
<el-drawer v-model="complaintOpen" title="投诉详情" size="min(640px,100vw)" :close-on-click-modal="!complaintSaving" :close-on-press-escape="!complaintSaving" :show-close="!complaintSaving">
  <div v-loading="complaintLoading" class="complaint-detail">
    <el-empty v-if="complaintError" description="详情加载失败，请重试"><el-button @click="showComplaint({id:complaintId})">重新加载</el-button></el-empty>
    <template v-if="complaintDetail">
      <div class="drawer-title"><span class="state-pill">{{texts[complaintDetail.complaint.status]}}</span><h2>{{complaintDetail.order.title}}</h2><p class="muted">工单编号：{{complaintDetail.order.orderNo}}</p></div>
      <div class="detail-pairs"><div><span>投诉居民</span><strong>{{complaintDetail.order.residentName}}</strong></div><div><span>投诉时间</span><strong>{{date(complaintDetail.complaint.createdAt)}}</strong></div><div><span>维修人员</span><strong>{{complaintDetail.order.workerName||'待分配'}}</strong></div><div><span>工单状态</span><strong>{{statusNames[complaintDetail.order.status]}}</strong></div></div>
      <div class="detail-address">{{complaintDetail.order.communityName}} · {{complaintDetail.order.buildingNo}}栋 {{complaintDetail.order.unitNo}}单元 {{complaintDetail.order.roomNo}}室</div>
      <h3>投诉内容</h3><p class="description">{{complaintDetail.complaint.content}}</p>
      <h3 class="detail-section-title">关联报修描述</h3><p class="description">{{complaintDetail.order.description}}</p>
      <h3 class="detail-section-title">处理记录</h3>
      <el-timeline><el-timeline-item v-for="log in complaintDetail.logs" :key="log.id" :timestamp="date(log.createdAt)" placement="top"><strong>{{texts[log.newStatus]}} · {{log.operatorName||'处理人员'}}</strong><p class="description">{{log.content}}</p></el-timeline-item></el-timeline>
      <template v-if="complaintDetail.complaint.status!=='CLOSED'">
        <h3 class="detail-section-title">{{complaintDetail.complaint.status==='OPEN'?'受理投诉':'提交处理结果'}}</h3>
        <el-form label-position="top" @submit.prevent="processComplaint"><el-form-item label="处理说明"><el-input v-model="complaintResolution" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="说明受理安排或处理结果，居民可在小程序中查看。" :disabled="complaintSaving"/></el-form-item><el-button type="primary" :loading="complaintSaving" :disabled="!complaintResolution.trim()" @click="processComplaint">{{complaintDetail.complaint.status==='OPEN'?'确认受理':'提交结果并关闭投诉'}}</el-button></el-form>
      </template>
      <el-alert v-else title="投诉已关闭，处理记录已保留。" type="success" :closable="false"/>
    </template>
  </div>
</el-drawer>
<el-dialog v-model="open" :title="form.editing?(section==='users'?'编辑用户':'配置角色权限'):'新建记录'" width="min(580px,95vw)"><el-form label-position="top" @submit.prevent="form.editing?(section==='users'?saveEditedUser():saveEditedRole()):save()">
<template v-if="section==='users'"><el-form-item label="账号"><el-input v-model="form.username" :disabled="form.editing"/></el-form-item><el-form-item label="姓名 / 昵称"><el-input v-model="form.nickname"/></el-form-item><el-form-item v-if="form.editing" label="联系电话"><el-input v-model="form.phone" maxlength="20" placeholder="留空可清除"/></el-form-item><el-form-item :label="form.editing?'新密码（留空不修改）':'初始密码（12到72位）'"><el-input v-model="form.password" type="password" show-password/></el-form-item><el-form-item label="角色"><el-checkbox-group v-model="form.roles" :disabled="form.editing&&form.id===session.user?.id"><el-checkbox v-for="role in roles" :key="role.id" :value="role.roleCode">{{role.roleName}}</el-checkbox></el-checkbox-group></el-form-item></template>
<template v-if="section==='roles'"><el-form-item label="角色名称"><el-input v-model="form.roleName"/></el-form-item><el-form-item label="角色编码"><el-input v-model="form.roleCode" :disabled="form.editing" placeholder="大写字母和下划线"/></el-form-item><el-form-item label="操作权限"><el-checkbox-group v-model="form.permissions"><el-checkbox v-for="permission in permissions" :key="permission.id" :value="permission.permissionCode">{{permission.name}}</el-checkbox></el-checkbox-group></el-form-item></template>
<template v-if="['grants','workers'].includes(section)"><el-form-item label="服务账号"><el-select v-model="form.userId" class="full" filterable><el-option v-for="user in users.filter(u=>u.roles?.includes(section==='workers'?'WORKER':'ADMIN'))" :key="user.id" :label="`${user.nickname} (${user.username})`" :value="user.id"/></el-select></el-form-item></template>
<template v-if="['grants','notices'].includes(section)"><el-form-item label="所属小区"><el-select v-model="form.communityId" class="full"><el-option v-for="community in communities" :key="community.id" :label="community.name" :value="community.id"/></el-select></el-form-item></template>
<template v-if="section==='notices'"><el-form-item label="标题"><el-input v-model="form.title" maxlength="100"/></el-form-item><el-form-item label="公告内容"><el-input v-model="form.content" type="textarea" :rows="6"/></el-form-item></template>
<template v-if="section==='workers'"><el-form-item label="维修人员姓名"><el-input v-model="form.realName"/></el-form-item><el-form-item label="服务小区"><el-select v-model="form.communityIds" multiple class="full"><el-option v-for="community in communities" :key="community.id" :label="community.name" :value="community.id"/></el-select></el-form-item><el-form-item label="维修技能"><el-checkbox-group v-model="form.categoryIds"><el-checkbox v-for="category in categories" :key="category.id" :value="category.id">{{category.name}}</el-checkbox></el-checkbox-group></el-form-item><el-form-item label="接单容量"><el-input-number v-model="form.maxActiveOrders" :min="1" :max="100"/></el-form-item></template>
<template v-if="section==='base'"><template v-if="baseType==='communities'"><el-form-item label="小区名称"><el-input v-model="form.name"/></el-form-item><el-form-item label="地址"><el-input v-model="form.address"/></el-form-item></template><el-form-item v-if="baseType==='buildings'" label="楼栋编号"><el-input v-model="form.buildingNo"/></el-form-item><el-form-item v-if="baseType==='units'" label="单元编号"><el-input v-model="form.unitNo"/></el-form-item><template v-if="baseType==='rooms'"><el-form-item label="房号"><el-input v-model="form.roomNo"/></el-form-item><el-form-item label="楼层"><el-input-number v-model="form.floor" :min="-10" :max="200"/></el-form-item></template></template>
</el-form><template #footer><el-button @click="open=false">取消</el-button><el-button type="primary" :loading="saving" @click="form.editing?(section==='users'?saveEditedUser():saveEditedRole()):save()">保存</el-button></template></el-dialog>
<el-dialog v-model="moveOpen" :title="`搬家 · ${moveUser?.nickname??''}`" width="min(580px,95vw)"><p class="muted">原房屋绑定会撤销，新房屋将立即生效；已有工单仍保留报修时的地址。</p><el-form label-position="top"><el-form-item label="原房屋"><el-select v-model="moveForm.oldBindingId" class="full"><el-option v-for="address in moveUser?.addresses??[]" :key="address.bindingId" :value="address.bindingId" :label="format([address],'addresses')"/></el-select></el-form-item><el-form-item label="新小区"><el-select v-model="moveForm.communityId" class="full" @change="moveCommunity"><el-option v-for="item in moveCommunities" :key="item.id" :value="item.id" :label="item.name"/></el-select></el-form-item><el-form-item label="新楼栋"><el-select v-model="moveForm.buildingId" class="full" @change="moveBuilding"><el-option v-for="item in moveBuildings" :key="item.id" :value="item.id" :label="`${item.buildingNo}栋`"/></el-select></el-form-item><el-form-item label="新单元"><el-select v-model="moveForm.unitId" class="full" @change="moveUnit"><el-option v-for="item in moveUnits" :key="item.id" :value="item.id" :label="`${item.unitNo}单元`"/></el-select></el-form-item><el-form-item label="新房屋"><el-select v-model="moveForm.newRoomId" class="full"><el-option v-for="item in moveRooms" :key="item.id" :value="item.id" :label="`${item.roomNo}室`"/></el-select></el-form-item><el-form-item label="搬家原因"><el-input v-model="moveForm.reason" type="textarea" maxlength="497" :rows="3" placeholder="例如：住户迁入新房，已核实居住信息"/></el-form-item></el-form><template #footer><el-button @click="moveOpen=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveMove">确认变更</el-button></template></el-dialog></template>
