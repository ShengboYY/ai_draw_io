export type StepSummaryInput = {
  phase: string;
  nodeCount?: number;
  edgeCount?: number;
  note?: string;
  userRequest?: string;
};

const STEP_SUMMARY_TITLES: Record<string, string> = {
  analyzing: 'Working summary',
  drawing: 'Drawing progress',
  reviewing: 'Quality checklist',
  revising: 'Revision brief',
  thinking: 'Working summary',
};

const STEP_SUMMARY_LINES: Record<string, string[]> = {
  analyzing: [
    'Identify the requested action, diagram type, and canvas context.',
    'Organize the key elements, relationships, and style constraints.',
    'Confirm whether this turn needs a diagram update or a direct answer.',
  ],
  drawing: [
    'Generate a complete canvas draft with nodes, edges, labels, and styles.',
    'Keep the diagram readable while the final XML is assembled.',
  ],
  reviewing: [
    'Check layout spacing, overlap risk, edge routing, and label readability.',
    'Verify style consistency and Draw.io XML quality.',
    'Decide whether another revision pass is needed.',
  ],
  revising: [
    'Convert review feedback into specific drawing changes.',
    'Prioritize readability, relationship correctness, and missing elements.',
    'Prepare the next complete canvas draft.',
  ],
  thinking: [
    'Decide the next action from the latest stream event.',
    'Keep the response focused on the current Draw.io task.',
  ],
};

const RAW_STREAM_MARKERS = [
  '<mxCell',
  '<mxGraphModel',
  '"drawio_node"',
  '"drawio_edge"',
  '"drawio_done"',
  '"xml"',
  '"type"',
];

const cleanUserRequest = (request?: string | null) => {
  const normalized = request
    ?.replace(/\s+/g, ' ')
    .replace(/\[Context:[\s\S]*$/i, '')
    .trim() || '';

  if (!normalized) return 'waiting for request details';
  return normalized.length > 90 ? `${normalized.slice(0, 87).trim()}...` : normalized;
};

const inferRequestProfile = (request?: string | null) => {
  const lower = request?.toLowerCase() || '';
  const fallback = {
    diagramType: lower ? 'requested diagram' : 'unknown diagram type',
    analysisFocus: lower
      ? 'extract requested objects, relationships, layout intent, and style constraints'
      : 'identify diagram type, key elements, and requested operation',
    reviewChecks: [
      'Check whether rendered elements match the requested objects and relationships.',
      'Check whether the layout makes the requested flow or structure easy to scan.',
    ],
    revisionFocus: [
      'Adjust missing, unclear, or misplaced requested elements.',
      'Tighten labels, grouping, and edge direction around the user goal.',
    ],
    thinkingFocus: 'route the request by requested objects, relationships, and canvas action',
  };

  if (!lower) return fallback;

  // These profiles keep non-drawing steps tied to the user's diagram intent instead of generic templates.
  if (/流程|flowchart|process|审批|业务流/.test(lower)) {
    return {
      diagramType: 'flowchart/process diagram',
      analysisFocus: 'map process steps, decisions, branch labels, and start/end points',
      reviewChecks: [
        'Check that each process step follows the requested order.',
        'Check decision nodes, branch labels, merge points, and end states.',
        'Check whether the main path and exception paths are visually separate.',
      ],
      revisionFocus: [
        'Revise missing or out-of-order process steps.',
        'Clarify branch labels, merge points, and exception exits.',
        'Improve main-path alignment so the process reads from start to finish.',
      ],
      thinkingFocus: 'decide the next flowchart action from steps, decisions, and branch paths',
    };
  }

  if (/架构|architecture|系统|部署|微服务/.test(lower)) {
    return {
      diagramType: 'architecture diagram',
      analysisFocus: 'identify system boundaries, components, dependencies, and data paths',
      reviewChecks: [
        'Check gateway, services, stores, and external dependencies against the request.',
        'Check layer boundaries, dependency direction, and cross-service data paths.',
        'Check whether containers and groups make ownership clear.',
      ],
      revisionFocus: [
        'Revise missing components, stores, or external systems.',
        'Clarify dependency direction and cross-layer edges.',
        'Improve grouping around gateway, services, stores, and external dependencies.',
      ],
      thinkingFocus: 'track gateway, services, stores, and external dependencies before the next action',
    };
  }

  if (/类图|uml|class/.test(lower)) {
    return {
      diagramType: 'UML class diagram',
      analysisFocus: 'extract classes, attributes, methods, and UML relationships',
      reviewChecks: [
        'Check classes, interfaces, attributes, and methods against the request.',
        'Check inheritance, composition, aggregation, and association semantics.',
        'Check class grouping and compartment readability.',
      ],
      revisionFocus: [
        'Revise missing classes, members, or relationship arrows.',
        'Clarify UML relationship semantics and class responsibilities.',
        'Align related classes so model structure is easier to compare.',
      ],
      thinkingFocus: 'track classes, members, and UML relationships before the next action',
    };
  }

  if (/时序|sequence|调用链|交互/.test(lower)) {
    return {
      diagramType: 'sequence diagram',
      analysisFocus: 'order participants, messages, returns, and interaction timing',
      reviewChecks: [
        'Check participants and message order against the requested interaction.',
        'Check returns, async calls, and activation timing where needed.',
        'Check vertical spacing so time order stays readable.',
      ],
      revisionFocus: [
        'Revise missing participants or messages.',
        'Clarify return messages, async calls, and optional branches.',
        'Realign lifelines and message order for a cleaner timeline.',
      ],
      thinkingFocus: 'track participants, message order, and interaction timing before the next action',
    };
  }

  if (/\ber\b|数据库|表|schema|entity/.test(lower)) {
    return {
      diagramType: 'ER/database diagram',
      analysisFocus: 'identify entities, keys, cardinality, and table relationships',
      reviewChecks: [
        'Check entities, primary keys, foreign keys, and join tables against the request.',
        'Check cardinality labels and relationship direction.',
        'Check table alignment so keys and relationships can be compared.',
      ],
      revisionFocus: [
        'Revise missing entities, keys, or relationship cardinalities.',
        'Clarify many-to-many joins and foreign-key links.',
        'Group related tables to make the schema easier to inspect.',
      ],
      thinkingFocus: 'track entities, keys, cardinality, and table relationships before the next action',
    };
  }

  if (/用例|use case|actor|参与者/.test(lower)) {
    return {
      diagramType: 'use case diagram',
      analysisFocus: 'separate actors, system boundary, user goals, and include/extend links',
      reviewChecks: [
        'Check actors, use cases, and system boundary against the request.',
        'Check include and extend relationships for clear user goals.',
        'Check actor placement outside the boundary and use cases inside it.',
      ],
      revisionFocus: [
        'Revise missing actors, use cases, or boundary labels.',
        'Clarify include and extend links around user goals.',
        'Improve placement so actors and system scope are obvious.',
      ],
      thinkingFocus: 'track actors, system boundary, and user goals before the next action',
    };
  }

  if (/状态|state|生命周期/.test(lower)) {
    return {
      diagramType: 'state diagram',
      analysisFocus: 'organize states, transitions, guards, and terminal conditions',
      reviewChecks: [
        'Check states, transitions, guards, and terminal states against the request.',
        'Check lifecycle direction and retry/error paths.',
        'Check whether transition labels explain why state changes happen.',
      ],
      revisionFocus: [
        'Revise missing states, guards, or terminal transitions.',
        'Clarify lifecycle direction plus retry and error paths.',
        'Improve transition labels so state changes are understandable.',
      ],
      thinkingFocus: 'track states, transitions, guards, and terminal conditions before the next action',
    };
  }

  return fallback;
};

const getPhaseLines = (phase: string, userRequest?: string | null) => {
  const requestText = cleanUserRequest(userRequest);
  const requestProfile = inferRequestProfile(userRequest);

  if (phase === 'analyzing') {
    return [
      `Current request: ${requestText}.`,
      `Diagram type: ${requestProfile.diagramType}.`,
      `Analysis focus: confirm diagram type, then ${requestProfile.analysisFocus}.`,
      'Next decision: draw a new diagram, edit the current canvas, or answer directly.',
    ];
  }

  if (phase === 'reviewing') {
    return [
      `Review target: ${requestText}.`,
      `Diagram type: ${requestProfile.diagramType}.`,
      ...requestProfile.reviewChecks,
    ];
  }

  if (phase === 'revising') {
    return [
      `Revision target: ${requestText}.`,
      `Diagram type: ${requestProfile.diagramType}.`,
      ...requestProfile.revisionFocus,
    ];
  }

  if (phase === 'thinking') {
    return [
      `Current request: ${requestText}.`,
      `Likely diagram type: ${requestProfile.diagramType}.`,
      `Next focus: ${requestProfile.thinkingFocus}.`,
    ];
  }

  return [...(STEP_SUMMARY_LINES[phase] || STEP_SUMMARY_LINES.thinking)];
};

export const sanitizeStepNote = (content?: string | null) => {
  const trimmed = content?.trim() || '';
  if (!trimmed) return '';

  const compact = trimmed
    .replace(/```(?:json|xml)?/gi, '')
    .replace(/```/g, '')
    .replace(/\s+/g, ' ')
    .trim();

  // Keep the process panel readable by dropping raw XML, JSON, and punctuation-only stream fragments.
  if (!compact || /^[{}[\],:"]+$/.test(compact)) return '';
  if (RAW_STREAM_MARKERS.some(marker => compact.includes(marker))) return '';
  if (/^"?(id|xml|type|content)"?\s*[:：]/i.test(compact)) return '';

  const normalized = compact
    .replace(/^[-*•\s]+/, '')
    .replace(/^["']|["',]+$/g, '')
    .trim();

  if (normalized.length < 12 && !/[\u4e00-\u9fff]{6,}/.test(normalized)) return '';
  return normalized.length > 140 ? `${normalized.slice(0, 137).trim()}...` : normalized;
};

export const buildStepSummary = ({
  phase,
  nodeCount = 0,
  edgeCount = 0,
  note,
  userRequest,
}: StepSummaryInput) => {
  const normalizedPhase = STEP_SUMMARY_LINES[phase] ? phase : 'thinking';
  const title = STEP_SUMMARY_TITLES[normalizedPhase] || STEP_SUMMARY_TITLES.thinking;
  const lines = getPhaseLines(normalizedPhase, userRequest);

  if (normalizedPhase === 'drawing') {
    lines.unshift(`Current canvas: ${nodeCount} nodes, ${edgeCount} edges.`);
  }

  const safeNote = sanitizeStepNote(note);
  if (safeNote) {
    lines.push(`Current note: ${safeNote}`);
  }

  // This is a user-facing progress summary, not a raw model reasoning transcript.
  return [
    `${title}:`,
    ...lines.map(line => `- ${line}`),
  ].join('\n');
};
