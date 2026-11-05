import { listMetadata, type MetadataObject } from '../../../services/data';

/* 后端单页上限 100；资源树要覆盖整个数据源的候选对象，按 cursor 续拉到末尾。
   50 页上限只用于防御异常游标导致的死循环，真实数据源远小于此规模。 */
const METADATA_PAGE_SIZE = 100;
const METADATA_MAX_PAGES = 50;

export async function listAllMetadata(datasourceId: string, signal: AbortSignal): Promise<MetadataObject[]> {
  const items: MetadataObject[] = [];
  let cursor: string | undefined;
  for (let page = 0; page < METADATA_MAX_PAGES; page += 1) {
    const { data } = await listMetadata({ datasourceId, pageSize: METADATA_PAGE_SIZE, cursor, signal });
    items.push(...data.items);
    if (!data.nextCursor) return items;
    cursor = data.nextCursor;
  }
  throw new Error('metadata cursor did not terminate');
}
