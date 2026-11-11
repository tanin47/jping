
// Whether the add URL form and the delete buttons are shown. The server sets it in index.html.
// @ts-expect-error defined globally
export const IS_ADMIN: boolean = window.IS_ADMIN

// Sends a JSON request and returns the parsed body, or throws an Error with the server's message.
export async function api(method: string, path: string, body?: unknown): Promise<any> {
  const response = await fetch(path, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  let json: any = null;
  try {
    json = await response.json();
  } catch {
    // Non-JSON response, e.g. a server crash page.
  }

  if (!response.ok) {
    throw new Error(json?.error ?? `The request failed (HTTP ${response.status}).`);
  }
  return json;
}
