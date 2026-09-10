<script setup lang="ts">
import { type Comment, coreApiClient } from '@halo-dev/api-client';
import { Toast, VDropdownItem } from '@halo-dev/components';
import { useQueryClient } from '@tanstack/vue-query';
import { computed, shallowRef } from 'vue';

const props = defineProps<{ getComment: () => Comment }>();
const comment = computed(() => props.getComment());
const queryClient = useQueryClient();
const submitting = shallowRef(false);

async function togglePin() {
  if (submitting.value) {
    return;
  }
  submitting.value = true;
  try {
    const name = comment.value.metadata.name;
    const { data: current } = await coreApiClient.content.comment.getComment({
      name,
    });
    const top = !current.spec.top;
    let priority = 0;
    if (top) {
      const { data } = await coreApiClient.content.comment.listComment({
        page: 1,
        size: 1,
        fieldSelector: ['spec.top=true'],
        sort: ['spec.priority,asc'],
      });
      priority = Math.min(0, data.items[0]?.spec.priority ?? 0) - 1;
      if (priority < -2147483648) {
        throw new Error('评论置顶顺序已达到上限');
      }
    }
    current.spec.top = top;
    current.spec.priority = priority;
    await coreApiClient.content.comment.updateComment({
      name,
      comment: current,
    });
    await queryClient.invalidateQueries({ queryKey: ['core:comments'] });
    Toast.success(top ? '评论已置顶' : '已取消置顶');
  } catch (error) {
    console.error(error);
    Toast.error('操作失败，请刷新后重试');
  } finally {
    submitting.value = false;
  }
}
</script>
<template>
  <VDropdownItem :disabled="submitting" @click="togglePin">
    {{ comment.spec.top ? '取消置顶' : '置顶' }}
  </VDropdownItem>
</template>
