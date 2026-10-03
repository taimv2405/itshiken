import { APP_URL } from '@/src/utils/app-url';
import Home from '@/src/views/Home';
import { getDictionary } from '@/src/utils/dictionaries';
import type { Locale } from '@/src/utils/i18n';
import { Metadata } from 'next';
import { examService } from '@/src/services/examService';

const baseUrl = APP_URL;
export async function generateMetadata({params}: { params: Promise<{ lang: string }>}): Promise<Metadata> {
    const { lang } = await  params;

    return {
        alternates: {
            canonical: `${baseUrl}/${lang}`,
            languages: {
                'x-default': `${baseUrl}/en`,
                vi: `${baseUrl}/vi`,
                en: `${baseUrl}/en`,
                ja: `${baseUrl}/ja`,
            },
        },
    };
}

export default async function Page({params}: { params: Promise<{ lang: string }>}) {
    const { lang } = await  params;
    const t = await getDictionary(lang as Locale)
    const popularExams = await examService.getPopularExams().catch((error) => {
        console.error('Error fetching popular exams:', error);
        return [];
    });

    return <Home t={t} lang={lang} popularExams={popularExams} />;
}
