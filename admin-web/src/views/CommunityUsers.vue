<script setup lang="ts">
import { ref,onMounted } from 'vue'
import { ElMessage,ElMessageBox } from 'element-plus'
import { get,patch } from '../api'
const rows=ref<any[]>([]),page=ref(1),total=ref(0),busy=ref(false),saving=ref(false),open=ref(false),form=ref<any>({})
async function load(){busy.value=true;try{const r=await get('/system/community-users',{page:page.value,size:20});rows.value=r.records;total.value=r.total}finally{busy.value=false}}
function edit(row:any){form.value={...row,password:'',originalStatus:row.status};open.value=true}
async function save(){saving.value=true;try{const f=form.value,body:any={nickname:f.nickname,phone:f.phone};if(f.password)body.password=f.password;if(f.status!==f.originalStatus)body.status=f.status;if(body.password||body.status)await ElMessageBox.confirm('账号停用或重置密码会让该居民已有登录失效。','确认修改',{type:'warning'});await patch('/system/community-users/'+f.id,body);ElMessage.success('居民资料已更新');open.value=false;await load()}catch{}finally{saving.value=false}}
onMounted(load)
</script>
<template><div class="page-heading"><div><h1>居民账号</h1><p class="muted">仅显示本物业小区内已申请或已通过房屋绑定的居民。</p></div><el-button @click="load">刷新</el-button></div>
<el-table :data="rows" v-loading="busy"><el-table-column prop="username" label="登录账号"/><el-table-column prop="nickname" label="姓名 / 昵称"/><el-table-column prop="phone" label="联系电话"/><el-table-column label="房屋"><template #default="{row}">{{row.addresses?.map((a:any)=>a.communityName+' '+a.buildingNo+'栋'+a.roomNo+'室').join('、')||'待审核绑定'}}</template></el-table-column><el-table-column label="账号状态"><template #default="{row}">{{row.status==='ENABLED'?'启用':'停用'}}</template></el-table-column><el-table-column label="操作"><template #default="{row}"><el-button link type="primary" @click="edit(row)">管理账号</el-button></template></el-table-column></el-table>
<el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev,pager,next" @current-change="load"/>
<el-dialog v-model="open" title="管理居民账号" width="480px"><el-form label-position="top"><el-form-item label="姓名 / 昵称"><el-input v-model="form.nickname" maxlength="50"/></el-form-item><el-form-item label="联系电话"><el-input v-model="form.phone" maxlength="20"/></el-form-item><el-form-item label="新密码（留空不修改）"><el-input v-model="form.password" type="password" show-password/></el-form-item><el-form-item label="账号状态"><el-select v-model="form.status"><el-option value="ENABLED" label="启用"/><el-option value="DISABLED" label="停用"/></el-select></el-form-item></el-form><template #footer><el-button @click="open=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template></el-dialog></template>
