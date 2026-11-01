<script setup lang="ts">
import { onMounted,ref } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import { ElMessage,ElMessageBox } from 'element-plus'
import { get,post,date } from '../api'

const labels:Record<string,string>={PENDING:'等待发送',PROCESSING:'发送中',SENT:'发送成功',FAILED:'发送失败'}
const notes:Record<string,string>={PENDING:'等待后台任务尝试投递',PROCESSING:'已由发送任务领取',SENT:'微信接口已接受',FAILED:'自动重试已停止'}
const status=ref('FAILED'),summary=ref<Record<string,number>>({}),rows=ref<any[]>([]),total=ref(0),page=ref(1),busy=ref(false),failed=ref(false),retrying=ref('')
let requestVersion=0
async function load(){
  const version=++requestVersion;busy.value=true;failed.value=false
  try{const [records,counts]=await Promise.all([get('/notifications/outbox',{status:status.value,page:page.value,size:20}),get('/notifications/outbox/summary')]);if(version===requestVersion){rows.value=records.records;total.value=records.total;summary.value=counts}}
  catch{if(version===requestVersion)failed.value=true}
  finally{if(version===requestVersion)busy.value=false}
}
function selectStatus(value:string){status.value=value;page.value=1;void load()}
async function retry(row:any){
  if(retrying.value)return
  try{await ElMessageBox.confirm('请确认模板和订阅配置已恢复。重试将重新尝试发送，外部平台可能重复投递。','重试微信通知',{confirmButtonText:'确认重试',cancelButtonText:'取消',type:'warning'})}catch{return}
  retrying.value=String(row.id)
  try{await post(`/notifications/outbox/${row.id}/retry`);ElMessage.success('已加入待发送队列，后台将在下一次扫描时尝试发送');await load()}catch{}finally{retrying.value=''}
}
onMounted(load)
</script>

<template>
  <div class="page-heading"><div><span class="eyebrow">通知管理 · 投递有记录</span><h1>微信通知投递</h1><p class="muted">查看接单通知的发送状态，在问题修复后重试失败记录。</p></div><button class="secondary-button" :disabled="busy" @click="load"><RefreshCw :size="16"/>刷新</button></div>
  <div class="overview-grid delivery-summary"><button v-for="(label,value) in labels" :key="value" class="metric" :class="{selected:status===value}" :aria-pressed="status===value" @click="selectStatus(value)"><span>{{label}}</span><strong :class="{warning:value==='FAILED'}">{{summary[value]??'—'}}</strong><small>{{notes[value]}}</small></button></div>
  <section class="panel">
    <div class="panel-toolbar"><div class="panel-title"><h2>{{labels[status]}}</h2><span>{{total}} 条</span></div><el-select v-model="status" aria-label="通知发送状态" class="status-filter" @change="selectStatus"><el-option v-for="(label,value) in labels" :key="value" :value="value" :label="label"/></el-select></div>
    <el-alert v-if="failed" type="error" title="发送记录加载失败，请点击刷新重试。" :closable="false"/>
    <el-table :data="rows" v-loading="busy" empty-text="当前状态下暂无微信投递记录">
      <el-table-column prop="id" label="记录编号" width="100"/>
      <el-table-column prop="recipientId" label="收件用户编号" width="130"/>
      <el-table-column label="状态 / 尝试次数" width="160"><template #default="{row}"><el-tag :type="row.status==='FAILED'?'danger':row.status==='SENT'?'success':'info'">{{labels[row.status]}}</el-tag><div class="subline">已尝试 {{row.attempts}} 次</div></template></el-table-column>
      <el-table-column label="最近失败原因" min-width="300"><template #default="{row}"><span class="failure-text">{{row.lastError||'—'}}</span></template></el-table-column>
      <el-table-column label="下次重试" width="150"><template #default="{row}">{{date(row.nextRetryAt)}}</template></el-table-column>
      <el-table-column label="更新时间" width="150"><template #default="{row}">{{date(row.updatedAt)}}</template></el-table-column>
      <el-table-column label="操作" width="125" fixed="right"><template #default="{row}"><el-button v-if="row.status==='FAILED'" link type="primary" :loading="retrying===String(row.id)" :disabled="!!retrying" @click="retry(row)">重新发送</el-button><span v-else class="muted">{{row.status==='SENT'?'已完成':'自动处理'}}</span></template></el-table-column>
    </el-table>
    <div class="pagination"><span>每次最多自动尝试 5 次，人工重试会记录操作审计</span><el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="prev,pager,next" @current-change="load"/></div>
  </section>
  <p class="muted">发送成功表示微信接口接受了请求，手机是否收到仍需真机确认。居民未授权订阅时不会产生微信发送记录，站内消息仍可查看。</p>
</template>

<style scoped>
.delivery-summary .metric{text-align:left;cursor:pointer;transition:border-color .15s}.delivery-summary .metric.selected{border-color:#0b7774;box-shadow:0 0 0 1px #0b7774}.delivery-summary .metric:hover{background:#f8fcfb}.status-filter{width:160px}.failure-text{white-space:pre-wrap;overflow-wrap:anywhere}
</style>
