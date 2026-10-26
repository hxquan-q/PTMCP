import type {
  DatasourceDelete,
  DatasourcePage,
  DatasourceRead,
  JobView,
  MetadataDetail,
  MetadataDictionary,
  MetadataItem,
  MetadataPage,
} from '../services/data';

type WireIdentifiers<T, Keys extends keyof T> = { [Key in keyof T]: Key extends Keys ? T[Key] | number : T[Key] };
type DatasourceDto = WireIdentifiers<DatasourceRead, 'id' | 'version' | 'configVersion' | 'resourceVersion' | 'activeSnapshotId'>;
type MetadataItemDto = WireIdentifiers<MetadataItem, 'id' | 'namespaceId' | 'generation' | 'snapshotId' | 'snapshotVersion'>;
type DictionaryDto = WireIdentifiers<MetadataDictionary, 'ownerUserId' | 'version'>;
type MetadataDetailDto = Omit<
  WireIdentifiers<MetadataDetail, 'id' | 'namespaceId' | 'generation' | 'snapshotId' | 'snapshotVersion'>,
  'dictionary'
> & {
  dictionary: DictionaryDto;
};

/** IDs are strings in the UI; reject numbers whose original identity is already lost. */
function identifier(value: string | number): string {
  if (typeof value === 'string') return value;
  if (typeof value === 'number' && Number.isSafeInteger(value)) return String(value);
  throw new Error('服务端资源标识或版本无效，请重新读取。');
}

function nullableIdentifier(value: string | number | null): string | null {
  return value === null ? null : identifier(value);
}

export function adaptDatasource(dto: DatasourceDto): DatasourceRead {
  return {
    ...dto,
    id: identifier(dto.id),
    version: dto.version === undefined ? undefined : identifier(dto.version),
    configVersion: identifier(dto.configVersion),
    resourceVersion: identifier(dto.resourceVersion),
    activeSnapshotId: nullableIdentifier(dto.activeSnapshotId),
  };
}

export function adaptDatasourcePage(dto: Omit<DatasourcePage, 'items'> & { items: DatasourceDto[] }): DatasourcePage {
  return { ...dto, items: dto.items.map(adaptDatasource) };
}

export function adaptDatasourceDelete(dto: WireIdentifiers<DatasourceDelete, 'id' | 'version'>): DatasourceDelete {
  return { ...dto, id: identifier(dto.id), version: identifier(dto.version) };
}

export function adaptJob(dto: WireIdentifiers<JobView, 'id' | 'datasourceId' | 'snapshotId'>): JobView {
  return { ...dto, id: identifier(dto.id), datasourceId: identifier(dto.datasourceId), snapshotId: nullableIdentifier(dto.snapshotId) };
}

export function adaptMetadataItem(dto: MetadataItemDto): MetadataItem {
  return {
    ...dto,
    id: identifier(dto.id),
    namespaceId: identifier(dto.namespaceId),
    generation: identifier(dto.generation),
    snapshotId: identifier(dto.snapshotId),
    snapshotVersion: identifier(dto.snapshotVersion),
  };
}

export function adaptMetadataPage(dto: Omit<MetadataPage, 'items'> & { items: MetadataItemDto[] }): MetadataPage {
  return { ...dto, items: dto.items.map(adaptMetadataItem) };
}

export function adaptMetadataDetail(dto: MetadataDetailDto): MetadataDetail {
  return {
    ...dto,
    ...adaptMetadataItem(dto),
    dictionary: { ...dto.dictionary, ownerUserId: nullableIdentifier(dto.dictionary.ownerUserId), version: identifier(dto.dictionary.version) },
  };
}
