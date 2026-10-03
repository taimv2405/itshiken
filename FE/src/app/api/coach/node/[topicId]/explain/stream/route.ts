import { NextRequest } from 'next/server';
import { BE_URL } from '@/src/utils/be-url.mjs';

export const runtime = 'edge';
export const dynamic = 'force-dynamic';

export async function GET(
    req: NextRequest,
    { params }: { params: Promise<{ topicId: string }> }
) {
    const userId = req.nextUrl.searchParams.get('userId');
    if (!userId) {
        return new Response('Missing userId', { status: 400 });
    }

    const { topicId } = await params;
    const cookie = req.headers.get('cookie') ?? '';

    try {
        const beRes = await fetch(
            `${BE_URL}/api/coach/node/${topicId}/explain/stream?userId=${userId}`,
            {
                headers: {
                    cookie,
                    accept: 'text/event-stream',
                    'cache-control': 'no-cache',
                },
                cache: 'no-store',
            }
        );

        if (!beRes.ok || !beRes.body) {
            return new Response('Backend error', { status: beRes.status });
        }

        return new Response(beRes.body, {
            headers: {
                'Content-Type': 'text/event-stream',
                'Cache-Control': 'no-cache, no-transform',
                'Connection': 'keep-alive',
                'X-Accel-Buffering': 'no',
            },
        });
    } catch {
        return new Response('Stream failed', { status: 500 });
    }
}
