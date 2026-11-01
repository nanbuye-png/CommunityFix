<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { request,date } from '../../api'
import { useSession } from '../../stores/session'
const rows=ref<any[]>([]),page=ref(1),total=ref(0),labels:Record<string,string>={PENDING:'等待接单',ACCEPTED:'已接单',REJECTED:'已拒单',REVOKED:'已撤回',FINISHED:'已提交维修结果'}
async function load(reset=true){if(!useSession().requireLogin('WORKER'))return;if(reset)page.value=1;const result=await request('/workers/history','GET',{page:page.value,size:20});rows.value=reset?result.records:[...rows.value,...result.records];total.value=result.total}
onShow(()=>load())
</script>
<template><view class="page"><view class="eyebrow">每一次维修，都有记录</view><view class="page-title">历史维修</view><view v-if="!rows.length" class="empty">暂无维修历史</view><view v-for="row in rows" :key="row.id" class="card"><view class="row"><text class="small">第{{row.roundNo}}轮 · {{row.orderNo}}</text><text class="pill">{{labels[row.status]}}</text></view><view class="order-title">{{row.title}}</view><view class="muted">派单时间：{{date(row.assignedAt)}}</view><view v-if="row.endedAt" class="muted">结束时间：{{date(row.endedAt)}}</view></view><button v-if="rows.length<total" class="secondary" @click="page++;load(false)">加载更多</button></view></template>
