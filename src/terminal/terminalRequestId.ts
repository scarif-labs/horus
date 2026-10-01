import {createRequestIdFactory} from './requestIds';

/** Request IDs for the terminal screen and its overlays, from one counter. */
export const nextRequestId = createRequestIdFactory('terminal');
