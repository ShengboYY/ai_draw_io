import type { DiagramSummaryResponseDTO } from '@/types/api';

// Filter tabs mirror the two broad kinds the backend labels diagrams with:
// freeform illustrations vs. every structured diagram type.
export type DiagramFilter = 'all' | 'diagrams' | 'illustrations';
export type DiagramSortMode = 'recent' | 'oldest' | 'name' | 'type';

export const DIAGRAM_FILTERS: { id: DiagramFilter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'diagrams', label: 'Diagrams' },
  { id: 'illustrations', label: 'Illustrations' },
];

export const DIAGRAM_SORTS: { id: DiagramSortMode; label: string }[] = [
  { id: 'recent', label: 'recent' },
  { id: 'oldest', label: 'oldest' },
  { id: 'name', label: 'name' },
  { id: 'type', label: 'type' },
];

export type DiagramCategoryToken =
  | 'flowchart'
  | 'architecture'
  | 'uml'
  | 'sequence'
  | 'er'
  | 'usecase'
  | 'state'
  | 'mindmap'
  | 'illustration'
  | 'blank'
  | 'diagram';

export const CATEGORY_LABELS: Record<DiagramCategoryToken, string> = {
  flowchart: '流程图',
  architecture: '架构图',
  uml: '类图',
  sequence: '时序图',
  er: 'ER图',
  usecase: '用例图',
  state: '状态图',
  mindmap: '思维导图',
  illustration: '插画',
  blank: '空白',
  diagram: '通用图',
};

const KNOWN_CATEGORIES = new Set<DiagramCategoryToken>(Object.keys(CATEGORY_LABELS) as DiagramCategoryToken[]);

const STRUCTURED_CATEGORY_KEYWORDS: { category: DiagramCategoryToken; keywords: string[] }[] = [
  { category: 'flowchart', keywords: ['流程', '审批', '业务流', 'flowchart', 'process'] },
  { category: 'architecture', keywords: ['架构', '系统', '部署', '微服务', 'architecture'] },
  { category: 'uml', keywords: ['类图', 'uml', 'class'] },
  { category: 'sequence', keywords: ['时序', '调用链', '交互', 'sequence'] },
  { category: 'er', keywords: ['数据库', '数据表', '表', 'schema', 'entity', 'er'] },
  { category: 'usecase', keywords: ['用例', '参与者', 'actor', 'use case', 'usecase'] },
  { category: 'state', keywords: ['状态', '生命周期', 'state'] },
  { category: 'mindmap', keywords: ['思维导图', '脑图', 'mindmap', '概念图'] },
];

const ILLUSTRATION_KEYWORDS = [
  '插画',
  'cute',
  'draw a',
  'draw an',
  '画只',
  '画一只',
  '猫',
  '狗',
  '动物',
  '头像',
  '物体',
  'cat',
  'dog',
  'animal',
  'pet',
  'bird',
  'fish',
  'car',
  'tree',
  'flower',
];

const normalizeRawCategory = (diagramType?: string): DiagramCategoryToken | null => {
  const raw = (diagramType || '').trim().toLowerCase();
  if (!raw || raw === 'none') return 'blank';
  if (raw === 'uml_class') return 'uml';
  if (raw === 'concept') return 'mindmap';
  if (raw === 'others') return 'diagram';
  if (raw === 'basic') return null;
  return KNOWN_CATEGORIES.has(raw as DiagramCategoryToken) ? (raw as DiagramCategoryToken) : null;
};

export const inferCategoryFromTitle = (title?: string): DiagramCategoryToken => {
  const normalized = (title || '').trim().toLowerCase();
  if (!normalized) return 'diagram';
  if (normalized.includes('空画布') || normalized.includes('空白') || normalized.includes('blank')) return 'blank';

  for (const rule of STRUCTURED_CATEGORY_KEYWORDS) {
    if (rule.keywords.some(keyword => normalized.includes(keyword))) {
      return rule.category;
    }
  }

  // Title inference rescues old "basic" rows whose only useful category signal is their name.
  if (ILLUSTRATION_KEYWORDS.some(keyword => normalized.includes(keyword))) {
    return 'illustration';
  }

  return 'diagram';
};

// The backend leaves diagramType empty (or "none") for a fresh, still-blank canvas.
export const categoryLabel = (diagram: DiagramSummaryResponseDTO): DiagramCategoryToken => {
  const raw = (diagram.diagramType || '').trim().toLowerCase();
  const normalized = normalizeRawCategory(raw);
  return normalized || inferCategoryFromTitle(diagram.title);
};

export const categoryDisplayLabel = (diagram: DiagramSummaryResponseDTO) => CATEGORY_LABELS[categoryLabel(diagram)];

export const isIllustration = (diagram: DiagramSummaryResponseDTO) => categoryLabel(diagram) === 'illustration';

const updatedAtMillis = (diagram: DiagramSummaryResponseDTO) => {
  if (!diagram.updatedAt) return 0;
  const millis = new Date(diagram.updatedAt).getTime();
  return Number.isNaN(millis) ? 0 : millis;
};

const titleForSort = (diagram: DiagramSummaryResponseDTO) => (diagram.title || 'Untitled Diagram').toLowerCase();

export interface DiagramLibraryView {
  query: string;
  filter: DiagramFilter;
  sort: DiagramSortMode;
}

// Single client-side pipeline over the fully-loaded workspace list:
// tab filter -> text search (title + category) -> sort.
export const applyDiagramLibraryView = (
  diagrams: DiagramSummaryResponseDTO[],
  { query, filter, sort }: DiagramLibraryView,
): DiagramSummaryResponseDTO[] => {
  const needle = query.trim().toLowerCase();

  const filtered = diagrams.filter(diagram => {
    if (filter === 'illustrations' && !isIllustration(diagram)) return false;
    if (filter === 'diagrams' && isIllustration(diagram)) return false;
    if (needle) {
      const category = categoryLabel(diagram);
      const haystack = `${diagram.title || ''} ${category} ${CATEGORY_LABELS[category]}`.toLowerCase();
      if (!haystack.includes(needle)) return false;
    }
    return true;
  });

  const sorted = [...filtered];
  switch (sort) {
    case 'oldest':
      sorted.sort((a, b) => updatedAtMillis(a) - updatedAtMillis(b));
      break;
    case 'name':
      sorted.sort((a, b) => titleForSort(a).localeCompare(titleForSort(b)));
      break;
    case 'type':
      // Group by category, then keep the most recent first inside each group.
      sorted.sort((a, b) => categoryLabel(a).localeCompare(categoryLabel(b)) || updatedAtMillis(b) - updatedAtMillis(a));
      break;
    case 'recent':
    default:
      sorted.sort((a, b) => updatedAtMillis(b) - updatedAtMillis(a));
      break;
  }
  return sorted;
};

// Tab counts always reflect the full library, independent of the active search text.
export const countByFilter = (diagrams: DiagramSummaryResponseDTO[], filter: DiagramFilter) =>
  applyDiagramLibraryView(diagrams, { query: '', filter, sort: 'recent' }).length;
