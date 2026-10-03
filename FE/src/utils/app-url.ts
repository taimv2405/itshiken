const appUrl = process.env.NEXT_PUBLIC_APP_URL;

if (!appUrl) {
    throw new Error('NEXT_PUBLIC_APP_URL is required');
}

export const APP_URL = appUrl;
