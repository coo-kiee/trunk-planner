// The QR opens this one route; P0~P3 switch inside the client app so the fragment token has a single entry point.
import { PhoneApp } from '../screens/shell/PhoneApp.tsx';
import { readRelayBaseUrl } from '../shared/config/relay-url.ts';

export default function Page() {
  // Read per request, so one build runs against any relay address without a rebuild.
  return <PhoneApp relay={readRelayBaseUrl(process.env.PHONE_RELAY_BASE_URL)} />;
}
