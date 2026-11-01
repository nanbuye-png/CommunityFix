<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { request,date } from '../../api'
import { useSession } from '../../stores/session'
const rows=ref<any[]>([]),selected=ref(''),logs=ref<any[]>([]),page=ref(1),total=ref(0),labels:Record<string,string>={OPEN:'待受理',PROCESSING:'处理中',CLOSED:'已关闭'}
async function load(reset=true){if(!useSession().requireLogin('RESIDENT'))return;if(reset)page.value=1;const result=await request('/complaints','GET',{page:page.value,size:20});rows.value=reset?result.records:[...rows.value,...result.records];total.value=result.total}
onShow(()=>load())
async function detail(id:string){selected.value=selected.value===id?'':id;if(selected.value)logs.value=await request(`/complaints/${id}/logs`)}
</script>
<template><view class="page"><view class="eyebrow">服务反馈 · 有始有终</view><view class="page-title">投诉进度</view><view v-if="!rows.length" class="empty">暂无投诉记录<br>你可以在工单详情里反馈服务问题</view><view v-for="row in rows" :key="row.id" class="card" @click="detail(row.id)"><view class="row"><text class="order-title">{{row.title}}</text><text class="pill">{{labels[row.status]}}</text></view><view class="muted">{{row.content}}</view><view v-if="row.resolution" class="notice">物业回复：{{row.resolution}}</view><view class="small spaced">{{date(row.createdAt)}}</view><template v-if="selected===row.id"><view v-for="log in logs" :key="log.id" class="notice"><view>{{labels[log.newStatus]}} · {{date(log.createdAt)}}</view><view>{{log.content}}</view></view></template></view><button v-if="rows.length<total" class="secondary" @click="page++;load(false)">加载更多</button></view></template>
