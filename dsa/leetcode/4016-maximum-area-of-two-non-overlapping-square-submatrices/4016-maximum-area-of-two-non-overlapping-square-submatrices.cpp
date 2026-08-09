class Solution {
public:
    int maxArea(vector<vector<int>>& mat) {
        int n = size(mat);
        int m = size(mat[0]);
        vector<vector<int>> sqs(n+1,vector<int>(m+1,0));
        vector<int> dpr(n+1), dpc(m+1);
        int res = 0;
        int answer = 0;
        for(int i = 1 ; i <= n ; i++){
            for(int j = 1 ; j <= m ; j++){
                if(mat[i-1][j-1] != 0){
                    sqs[i][j] = min(sqs[i-1][j],min(sqs[i][j-1],sqs[i-1][j-1]))+1;
                }
                if(sqs[i][j]){
                    int r = i-sqs[i][j];
                    int c = j-sqs[i][j];
                    res = max(dpr[r],res);
                    res = min(res,sqs[i][j]);
                    if(res)
                        answer = max(answer, res*res);
                }
                dpc[j] = max(dpc[j-1],max(dpc[j],sqs[i][j]));
                dpr[i] = max(dpr[i-1],max(dpr[i],sqs[i][j]));
            }
        }
        for(int i = 0; i <= m ; i++){
            cout<<dpc[i]<<" ";
        }
        cout<<'\n';
        for(int i = 1 ; i <= n ; i++){
            for(int j = 1 ; j <= m ; j++){
                int res = 0;
                if(sqs[i][j]){
                    int c = j-sqs[i][j];
                    int res  = max(dpc[c],res);
                    res = min(res,sqs[i][j]);
                    answer = max(answer,res*res);
                    if(answer > 1){
                        cout<<dpc[c]<<" "<<res<<'\n';
                    }
                }
            }
        }
        return answer;
    }
};