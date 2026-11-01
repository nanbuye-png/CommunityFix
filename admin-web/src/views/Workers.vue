<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, patch } from '../api'
import { useSession } from '../stores/session'
const session=useSession(),root=computed(()=>session.roles.includes('SUPER_ADMIN'))
const rows=ref<any[]>([]),communities=ref<any[]>([]),categories=ref<any[]>([]),busy=ref(false),saving=ref(false),open=ref(false),mode=ref('create'),form=ref<any>({})
const candidates=ref<any[]>([]),query=ref(''),page=ref(1),total=ref(0)
const titles:Record<string,string>={create:'新建维修人员',link:'关联已有账号',account:'管理维修账号',profile:'工作状态与技能',services:'服务小区',owner:'变更负责小区'}
const names=(ids:any[],source:any[])=>ids?.map(id=>source.find(x=>String(x.id)===String(id))?.name||id).join('、')||'—'
async function load(){busy.value=true;try{rows.value=await get('/workers')}finally{busy.value=false}}
async function search(){const r=await get('/workers/account-candidates',{q:query.value,page:page.value,size:20});candidates.value=r.records;total.value=r.total}
async function edit(action:string,row?:any){
 mode.value=action
 form.value=row?{...row,communityIds:[...row.communityIds],categoryIds:[...row.categoryIds],password:'',status:row.accountStatus}:{username:'',password:'',realName:'',phone:'',ownerCommunityId:'',communityIds:[],categoryIds:[],maxActiveOrders:5}
 if(action==='link'){query.value='';page.value=1;await search()}
 open.value=true
}
function selectCandidate(id:any){const item=candidates.value.find(c=>c.id===id);if(item)form.value.realName=item.nickname}
async function save(){
 if(saving.value)return
 const f=form.value
 if(['create','link'].includes(mode.value)&&(!f.realName?.trim()||!f.ownerCommunityId||!f.communityIds.includes(f.ownerCommunityId)||!f.categoryIds.length)){ElMessage.warning('请填写姓名、负责小区、服务范围和技能');return}
 if(mode.value==='create'&&(!/^[a-zA-Z0-9_]{3,50}$/.test(f.username)||f.password.length<12||new TextEncoder().encode(f.password).length>72)){ElMessage.warning('账号应为3至50位字母、数字或下划线；密码至少12位且不超过72字节');return}
 saving.value=true
 try{
  if(mode.value==='create')await post('/workers/accounts',f)
  else if(mode.value==='link')await post('/workers',f)
  else if(mode.value==='account'){
   const body:any={realName:f.realName,phone:f.phone}
   if(f.password)body.password=f.password
   if(f.status!==f.accountStatus)body.status=f.status
   if(body.password||body.status)await ElMessageBox.confirm('密码重置或账号启停会影响该人员服务的所有小区，并使已有登录失效。','确认账号变更',{type:'warning'})
   await patch('/workers/'+f.id+'/account',body)
  }else if(mode.value==='profile')await patch('/workers/'+f.id,{workStatus:f.workStatus,maxActiveOrders:f.maxActiveOrders,categoryIds:f.categoryIds})
  else if(mode.value==='services')await patch('/workers/'+f.id+'/services',{communityIds:f.communityIds})
  else await patch('/workers/'+f.id+'/owner',{communityId:f.ownerCommunityId})
  open.value=false;ElMessage.success('已保存');await load()
 }catch{/* API errors are displayed by the shared interceptor. */}finally{saving.value=false}
}
onMounted(async()=>{[communities.value,categories.value]=await Promise.all([get('/manage/communities'),get('/repair/categories')]);await load()})
</script>
<template>
 <div class="page-heading"><div><h1>维修人员</h1><p class="muted">创建登录账号，管理负责人员与本辖区服务范围。</p></div><div class="action-row"><el-button @click="load">刷新</el-button><el-button v-if="root" @click="edit('link')">关联已有账号</el-button><el-button type="primary" @click="edit('create')">新建维修人员</el-button></div></div>
 <el-table :data="rows" v-loading="busy">
  <el-table-column prop="username" label="登录账号" min-width="140"/><el-table-column prop="realName" label="姓名" min-width="100"/>
  <el-table-column label="负责小区" min-width="150"><template #default="{row}">{{row.ownerCommunityName||(row.ownerCommunityId?'其他负责小区':'待分配负责小区')}}</template></el-table-column>
  <el-table-column label="服务小区" min-width="160"><template #default="{row}">{{names(row.communityIds,communities)}}</template></el-table-column>
  <el-table-column label="技能" min-width="150"><template #default="{row}">{{names(row.categoryIds,categories)}}</template></el-table-column>
  <el-table-column label="状态" min-width="120"><template #default="{row}">{{row.accountStatus==='DISABLED'?'账号停用':row.workStatus==='AVAILABLE'?'可接单':row.workStatus==='ON_LEAVE'?'休假中':'停止接单'}}</template></el-table-column>
  <el-table-column label="任务 / 容量" width="110"><template #default="{row}">{{row.activeOrders}} / {{row.maxActiveOrders}}</template></el-table-column>
  <el-table-column label="操作" width="310"><template #default="{row}"><el-button v-if="row.canManageAccount" link type="primary" @click="edit('account',row)">账号</el-button><el-button v-if="row.canManageProfile" link type="primary" @click="edit('profile',row)">工作与技能</el-button><el-button v-if="row.canManageServices" link type="primary" @click="edit('services',row)">服务范围</el-button><el-button v-if="row.canTransferOwner" link type="primary" @click="edit('owner',row)">归属</el-button></template></el-table-column>
 </el-table>
 <el-dialog v-model="open" :title="titles[mode]" width="560px" destroy-on-close>
 <el-form label-position="top">
  <template v-if="mode==='link'"><el-form-item label="搜索登录账号或姓名"><el-input v-model="query" @input="page=1;search()"/></el-form-item><el-form-item label="已有登录账号"><el-select v-model="form.userId" @change="selectCandidate"><el-option v-for="c in candidates" :key="c.id" :value="c.id" :label="c.username+'（'+c.nickname+'）'"/></el-select></el-form-item><el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="prev,pager,next" @current-change="search"/></template>
  <el-form-item v-if="mode==='create'||mode==='account'" label="登录账号"><el-input v-model="form.username" :disabled="mode==='account'" autocomplete="off"/></el-form-item>
  <el-form-item v-if="['create','link','account'].includes(mode)" label="姓名"><el-input v-model="form.realName" maxlength="50"/></el-form-item>
  <el-form-item v-if="mode==='create'||mode==='account'" label="联系电话"><el-input v-model="form.phone" maxlength="20"/></el-form-item>
  <el-form-item v-if="mode==='create'||mode==='account'" :label="mode==='create'?'初始密码（至少12位）':'新密码（留空不修改）'"><el-input v-model="form.password" type="password" show-password autocomplete="new-password"/></el-form-item>
  <el-form-item v-if="mode==='account'" label="账号状态"><el-select v-model="form.status"><el-option value="ENABLED" label="启用"/><el-option value="DISABLED" label="停用"/></el-select></el-form-item>
  <el-form-item v-if="['create','link','owner'].includes(mode)" label="负责小区"><el-select v-model="form.ownerCommunityId"><el-option v-for="c in communities.filter(c=>c.status==='ENABLED'&&(mode!=='owner'||form.communityIds.includes(c.id)))" :key="c.id" :value="c.id" :label="c.name"/></el-select></el-form-item>
  <el-form-item v-if="['create','link','services'].includes(mode)" label="服务小区"><el-select v-model="form.communityIds" multiple><el-option v-for="c in communities.filter(c=>mode!=='services'||root||form.canManageProfile||rows.find(r=>r.id===form.id)?.communityIds.includes(c.id))" :key="c.id" :value="c.id" :label="c.name" :disabled="c.status!=='ENABLED'&&!form.communityIds.includes(c.id)"/></el-select></el-form-item>
  <p v-if="mode==='services'" class="muted">仅修改当前可见的小区范围。存在未完成任务的服务关系不能撤销；跨物业首次共享由系统管理员设置。</p>
  <template v-if="['create','link','profile'].includes(mode)"><el-form-item label="维修技能"><el-checkbox-group v-model="form.categoryIds"><el-checkbox v-for="c in categories" :key="c.id" :value="c.id">{{c.name}}</el-checkbox></el-checkbox-group></el-form-item><el-form-item label="跨小区总接单容量"><el-input-number v-model="form.maxActiveOrders" :min="1" :max="100"/></el-form-item></template>
  <el-form-item v-if="mode==='profile'" label="工作状态"><el-select v-model="form.workStatus"><el-option value="AVAILABLE" label="可接单"/><el-option value="ON_LEAVE" label="休假中"/><el-option value="DISABLED" label="停止接单"/></el-select></el-form-item>
 </el-form>
 <template #footer><el-button @click="open=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
 </el-dialog>
</template>
