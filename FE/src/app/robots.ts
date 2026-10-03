import { APP_URL } from '@/src/utils/app-url';
import { MetadataRoute } from 'next';

const baseUrl = APP_URL;

export default function robots(): MetadataRoute.Robots {
    return {
        rules: [
            {
                userAgent: '*',
                allow: ['/'],
                disallow: [
                    // --- ADMIN & USER ---
                    '/admin/',
                    '/login/',
                    '/register/',
                    '/profile/',
                    '/dashboard/',
                    '/forgot-password/',

                    // --- API ---
                    '/api/v1',

                    // --- SEARCH & FILTER ---
                    '/search/',
                    '/?s=*',
                    '/exams?*category=*',
                    '/materials?*sort=*',
                ],
            },
        ],
        sitemap: `${baseUrl}/sitemap.xml`,
    };
}
