#include <bits/stdc++.h>
#include <ext/pb_ds/assoc_container.hpp>

using namespace std;
using namespace __gnu_pbds;

#define iambharatks                   \
    ios_base::sync_with_stdio(false); \
    cin.tie(NULL);                    \
    cout.tie(NULL)

const int MOD = 998244353;
const int N = 2e5 + 5;
double eps = 1e-12;
const int mod = 1e9 + 7;

void solve()
{
    int n;
    cin >> n;
    // fn(i) = fn(i-1) + fn(i)
    //         fn(i-2) + fn(2)
    //         .. fn(i-6) + fn(6)
    vector<int> dp(n + 1, 0);
    dp[0] = 1;
    for (int i = 1; i <= n; i++)
    {
        for (int j = 1; j <= 6; j++){
            if(i-j >= 0)
                dp[i] = (dp[i] + dp[i-j])%mod;
        } 
    }
    cout << dp[n] << "\n";
}

int main()
{
    iambharatks;
    long long t = 1;
    cin >> t;
    for (int it = 1; it <= t; it++)
    {
        // cout << "Case #" << it << ": ";
        solve();
    }
    return 0;
}