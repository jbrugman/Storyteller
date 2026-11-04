export type GraphAction = 'generate' | 'fill' | 'reset';

// The bundled interface only warns when the graph already holds data; unparseable JSON is treated as data.
export function graphHasData(graph: string): boolean {
  try {
    const parsed = JSON.parse(graph) as { entities?: Record<string, unknown>; facts?: unknown[] };
    return Object.keys(parsed.entities || {}).length > 0 || (parsed.facts || []).length > 0;
  } catch {
    return true;
  }
}

export function formatKnowledgeGraph(graph: unknown): string {
  return JSON.stringify(graph, null, 2);
}
