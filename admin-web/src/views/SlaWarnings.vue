<script setup lang="ts">
import { onMounted,ref } from 'vue'
import { useRouter } from 'vue-router'
import { RefreshCw } from 'lucide-vue-next'
import { get,date,statusNames } from '../api'

const router=useRouter(),rows=ref<any[]>([]),busy=ref(false),failed=ref(false),total=ref(0),page=ref(1)
const active=ref(true),phase=ref(''),level=ref<number|undefined>(),search=ref('')
const phases:Record<string,string>={RESPONSE:'响应阶段',COMPLETION:'维修阶段'}
const levels:Record<number,string>={1:'即将超时',2:'已经超时',3:'严重超时'}
let requestVersion=0
async function load(){
  const version=++requestVersion;busy.value=true;failed.value=false
  try{const result=await get('/sla/warnings',{page:page.value,size:20,active:active.value,phase:phase.value||undefined,level:level.value,search:search.value.trim()||undefined});if(version===requestVersion){rows.value=result.records;total.value=result.total}}
  catch{if(version===requestVersion)failed.value=true}
  finally{if(version===requestVersion)busy.value=false}
}
function query(){page.value=1;void load()}
function openOrder(row:any){void router.push({path:'/orders',query:{orderId:row.orderId}})}
onMounted(load)
</script>

<template>
  <div class="page-heading"><div><span class="eyebrow">服务时效 · 及时跟进</span><h1>工单时效预警</h1><p class="muted">集中查看需要关注的工单，进入工单继续审核、派单或跟进维修。</p></div><button class="secondary-button" :disabled="busy" @click="load"><RefreshCw :size="16"/>刷新</button></div>
  <section class="panel">
    <el-form class="warning-filters" inline @submit.prevent="query">
      <el-form-item label="记录范围"><el-select v-model="active" aria-label="预警记录范围" @change="query"><el-option :value="true" label="当前仍在预警阶段"/><el-option :value="false" label="全部记录（含历史）"/></el-select></el-form-item>
      <el-form-item label="阶段"><el-select v-model="phase" clearable placeholder="全部阶段" aria-label="预警阶段" @change="query"><el-option v-for="(label,value) in phases" :key="value" :value="value" :label="label"/></el-select></el-form-item>
      <el-form-item label="等级"><el-select v-model="level" clearable placeholder="全部等级" aria-label="预警等级" @change="query"><el-option v-for="(label,value) in levels" :key="value" :value="Number(value)" :label="label"/></el-select></el-form-item>
      <el-form-item label="工单"><el-input v-model="search" clearable maxlength="100" placeholder="编号或报修标题" @clear="query"/></el-form-item><el-form-item><el-button native-type="submit">查询</el-button></el-form-item>
    </el-form>
    <el-alert v-if="failed" type="error" title="预警记录加载失败，请点击刷新重试。" :closable="false"/>
    <el-table :data="rows" v-loading="busy" class="warning-table" empty-text="当前范围内暂无预警记录" @row-click="openOrder">
      <el-table-column label="报修事项" min-width="240"><template #default="{row}"><div class="order-title">{{row.title}}</div><div class="subline">{{row.orderNo}} · {{row.communityName}}</div></template></el-table-column>
      <el-table-column label="预警" width="120"><template #default="{row}"><el-tag :type="row.level===3?'danger':row.level===2?'warning':'info'">{{levels[row.level]}}</el-tag></template></el-table-column>
      <el-table-column label="阶段 / 轮次" width="140"><template #default="{row}">{{phases[row.phase]}}<div class="subline">第 {{row.roundNo}} 轮</div></template></el-table-column>
      <el-table-column label="截止时间" width="150"><template #default="{row}">{{date(row.deadlineAt)}}</template></el-table-column>
      <el-table-column label="当前工单" width="140"><template #default="{row}">{{statusNames[row.orderStatus]}}<div class="subline">{{Number(row.currentlyActive)===1?'仍在该阶段':'已离开该阶段'}}</div></template></el-table-column>
      <el-table-column label="负责人" width="110"><template #default="{row}">{{row.workerName||'待分配'}}</template></el-table-column>
      <el-table-column label="操作" width="120" fixed="right"><template #default="{row}"><el-button link type="primary" @click.stop="openOrder(row)">查看工单</el-button></template></el-table-column>
    </el-table>
    <div class="pagination"><span>共 {{total}} 条，每轮每阶段显示最高等级</span><el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="prev,pager,next" @current-change="load"/></div>
  </section>
  <p class="muted">系统每分钟检查时效；达到时限的 80%、100%、150% 分别记录一级、二级、三级预警。工单离开对应阶段后，预警仍保留在历史中。</p>
</template>

<style scoped>
.warning-filters{display:flex;flex-wrap:wrap;gap:0 18px;padding:24px 24px 4px}.warning-filters .el-form-item{margin-right:0}.warning-filters .el-select{width:168px}.warning-filters .el-input{width:210px}.warning-table :deep(.el-table__row){cursor:pointer}
</style>
