/** @typedef {'overview' | 'runs' | 'evalOverview' | 'evalRuns' | 'operations' | 'candidates' | 'cases' | 'datasets' | 'trace'} AdminSection */
/** @typedef {'overview' | 'evaluation' | 'traceAnalysis' | 'operations'} PrimaryNavId */
/** @typedef {{ href: string; label: string; section: AdminSection }} SecondaryNavItem */
/** @typedef {{ id: PrimaryNavId; href: string; label: string; sections: AdminSection[]; children: SecondaryNavItem[] }} PrimaryNavItem */

/** @type {PrimaryNavItem[]} */
export const primaryNavItems = [
  { id: 'overview', href: '/admin', label: 'Overview', sections: ['overview'], children: [] },
  {
    id: 'evaluation',
    href: '/admin/evaluations',
    label: 'Evaluation',
    sections: ['evalOverview', 'evalRuns', 'cases', 'datasets'],
    // Evaluation workflow pages live beneath their shared workspace entry.
    children: [
      { href: '/admin/evaluations', label: 'Overview', section: 'evalOverview' },
      { href: '/admin/eval-cases', label: 'Cases', section: 'cases' },
      { href: '/admin/eval-datasets', label: 'Datasets', section: 'datasets' },
      { href: '/admin/eval-runs', label: 'Runs', section: 'evalRuns' },
    ],
  },
  {
    id: 'traceAnalysis',
    href: '/admin/runs',
    label: 'Trace Analysis',
    sections: ['runs', 'trace', 'candidates'],
    // Trace inspection and its reviewed findings share one analysis workspace.
    children: [
      { href: '/admin/runs', label: 'Trace Runs', section: 'runs' },
      { href: '/admin/trace-findings', label: 'Findings', section: 'candidates' },
    ],
  },
  { id: 'operations', href: '/admin/eval-operations', label: 'Operations', sections: ['operations'], children: [] },
];

/** @param {AdminSection} active @returns {PrimaryNavId} */
export function primaryNavIdFor(active) {
  return primaryNavItems.find((item) => item.sections.includes(active))?.id || 'overview';
}
