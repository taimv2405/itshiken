function getBeUrl() {
    if (typeof window !== 'undefined') return '';

    if (!process.env.BE_URL) { 
        throw new Error('BE_URL is required');
    }

    return process.env.BE_URL;
}

export const BE_URL = getBeUrl();
