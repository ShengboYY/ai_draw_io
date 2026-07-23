import type { UploadStage } from './upload-machine';

const labels: Record<UploadStage, string> = {
  IDLE: '等待上传',
  HASHING: '正在校验文件',
  INITIATING: '正在创建上传',
  UPLOADING_BYTES: '正在上传文件',
  COMPLETING: '正在提交处理',
  PROCESSING: '正在安全扫描和索引',
  READY: '已就绪',
  PARTIAL_READY: '部分就绪',
  FAILED: '上传失败',
  REJECTED: '文件被拒绝',
  CANCELLED: '已取消',
};

export const MaterialProcessingBadge = ({ stage }: { stage: UploadStage }) => (
  <span className="rounded-full bg-stone-100 px-2.5 py-1 text-xs font-medium text-zinc-700">
    {labels[stage]}
  </span>
);
